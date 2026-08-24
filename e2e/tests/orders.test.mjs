import { http } from '../lib/http.mjs'
import { assert } from '../lib/assert.mjs'
import { env } from '../lib/env.mjs'

// UC004/005/006/007/008/009 — order lifecycle: checkout → pay → ship → deliver,
// cancel unpaid, cancel-paid (refund APPROVED), post-ship refund (REFUND_PENDING → REFUNDED),
// and legal-transition guards (409 on illegal state changes).
//
// This suite is self-contained: it logs in admin (used as the principal for both
// admin and user-side order operations — see the setup test for why), creates its
// own flash-sale products (one per order, because the one-per-user
// `seckill:{pid}:{userId}` gate blocks a user from buying the same product twice),
// opens each sale, and cleans up.
//
// Contract notes (verified against the backend):
//  - POST /checkout requires customerPhone + paymentMethod (WECHAT/ALIPAY/BANK_CARD) +
//    idempotencyKey; returns 202 + { orderId, status:'queued' }. orderId is a millis
//    correlation id, NOT the DB PK.
//  - The consumer persists the Order row (DB PK = auto-increment) within ~100ms and writes
//    order_id_map:{corrId} → realPk. The user-side GET /orders/{corrId} returns a SYNTHETIC
//    placeholder (totalInCents=0, id=corrId) while queued; the REAL row has totalInCents>0
//    and id = DB PK. We poll until totalInCents > 0 (mirrors the frontend's polling).
//  - USER mutating endpoints (pay, cancel, cancel-paid, confirm-receipt, refund-request)
//    accept the correlation id (resolveOrderId maps corrId→realPk).
//  - ADMIN mutating endpoints (ship, approve refund) take the DB PK directly — they do NOT
//    call resolveOrderId. So we capture realPk = orderData.id after polling and use it for
//    admin ship/approve; we use corrId for user endpoints + GET.
//  - 409 = CONFLICT (illegal state transition). 200 = success (Result<Void> has no data).

// Poll until the order row is the REAL persisted one (totalInCents > 0), not the queued
// synthetic placeholder. Returns the Order body (data.id = DB PK, data.status = PENDING).
async function pollUntilReal(orderId, token) {
  const deadline = Date.now() + env.POLL_TIMEOUT_MS
  while (Date.now() < deadline) {
    const res = await http.get(`/orders/${orderId}`, { token })
    if (res.body.data && res.body.data.totalInCents > 0) {
      return res.body.data
    }
    await new Promise(r => setTimeout(r, env.POLL_INTERVAL_MS))
  }
  throw new Error(`order ${orderId} did not reach a real persisted row within ${env.POLL_TIMEOUT_MS}ms`)
}

// Create a flash-sale product + open its sale (load stock into Redis so the Lua gate serves
// claims). Returns { productId, stock }. Each order uses its own product to dodge the
// one-per-user seckill gate.
async function createAndOpenSaleProduct(ctx, stock) {
  const res = await http.post('/products', {
    name: 'E2E Order Flow ' + Date.now() + '-' + Math.random().toString(36).slice(2, 7),
    priceInCents: 1999,
    stock,
    category: 'test',
    isFlashSale: true,
  }, { token: ctx.adminToken })
  assert(res.status === 200, `expected 200 creating product, got ${res.status}`)
  const productId = res.body.data.id
  const open = await http.post(`/admin/sales/${productId}`, {}, { token: ctx.adminToken })
  assert(open.status === 200, `expected 200 opening sale, got ${open.status}`)
  return { productId, stock }
}

// Checkout one unit of `productId` and poll until the real PENDING row exists.
// Returns { corrId, realPk }. Uses the suite's user token.
async function checkoutAndPoll(ctx, productId) {
  const idem = 'orders-' + Date.now() + '-' + Math.random().toString(36).slice(2, 7)
  const res = await http.post('/checkout', {
    items: [{ productId, quantity: 1 }],
    customerName: 't', customerEmail: 't@t', shippingAddress: 't',
    customerPhone: '13800000000',
    paymentMethod: 'WECHAT',
    idempotencyKey: idem,
  }, { token: ctx.userToken })
  assert(res.status === 202, `expected 202 on checkout, got ${res.status}`)
  assert(res.body.data.status === 'queued', `expected queued, got ${res.body.data.status}`)
  const corrId = res.body.data.orderId
  const order = await pollUntilReal(corrId, ctx.userToken)
  assert(order.status === 'PENDING', `expected PENDING after persist, got ${order.status}`)
  return { corrId, realPk: order.id }
}

export const suite = {
  name: 'orders',
  tests: [
    {
      name: 'setup: login admin (used as both admin + user token)',
      // The admin user is a valid authenticated principal, and /orders/** + /checkout only
      // require authentication (SecurityConfig: anyRequest().authenticated()), so admin's
      // token legitimately exercises the user-side order flows. Orders are owned by admin's
      // userId; the seckill one-per-user gate and CAS transitions behave identically.
      // This suite uses admin as its principal (not the registered ctx.userToken) so its
      // checkouts don't share the registered user's per-user /checkout rate-limit bucket
      // (RateLimitFilter: 10/user/60s) with the checkout suite.
      run: async (ctx) => {
        if (!ctx.adminToken) {
          const a = await http.post('/auth/login', env.ADMIN)
          assert(a.status === 200, `admin login expected 200, got ${a.status}`)
          ctx.adminToken = a.body.data.token
        }
        ctx.userToken = ctx.adminToken
        ctx.ordersProductIds = []
      },
    },
    {
      name: 'create flash-sale products for the 4 order flows + 2 guards, open their sales',
      run: async (ctx) => {
        // One product per order/guard: the one-per-user `seckill:{pid}:{userId}` gate blocks
        // a user from buying the same product twice, so each order needs its own product.
        ctx.prod1 = await createAndOpenSaleProduct(ctx, 5)
        ctx.prod2 = await createAndOpenSaleProduct(ctx, 5)
        ctx.prod3 = await createAndOpenSaleProduct(ctx, 5)
        ctx.prod4 = await createAndOpenSaleProduct(ctx, 5)
        ctx.prod5 = await createAndOpenSaleProduct(ctx, 5) // guard: confirm-receipt on PENDING
        ctx.prod6 = await createAndOpenSaleProduct(ctx, 5) // guard: ship a non-PAID order
        ctx.ordersProductIds.push(ctx.prod1.productId, ctx.prod2.productId,
                                  ctx.prod3.productId, ctx.prod4.productId,
                                  ctx.prod5.productId, ctx.prod6.productId)
      },
    },
    {
      name: 'UC005/006 checkout → pay → PENDING→PAID',
      run: async (ctx) => {
        const { corrId } = await checkoutAndPoll(ctx, ctx.prod1.productId)
        ctx.order1Corr = corrId
        const pay = await http.post(`/orders/${corrId}/pay`, {}, { token: ctx.userToken })
        assert(pay.status === 200, `expected 200 on pay, got ${pay.status}`)
        assert(pay.body.data.status === 'PAID', `expected PAID, got ${pay.body.data.status}`)
        ctx.order1Paid = true
      },
    },
    {
      name: 'admin ship the paid order → PAID→SHIPPED, tracking set',
      run: async (ctx) => {
        // Resolve the real DB PK (admin ship takes the PK, not the correlation id).
        const got = await http.get(`/orders/${ctx.order1Corr}`, { token: ctx.userToken })
        const realPk = got.body.data.id
        const trackNo = 'TRK-' + Date.now()
        const res = await http.post(`/admin/orders/${realPk}/ship`,
          { trackingNumber: trackNo }, { token: ctx.adminToken })
        assert(res.status === 200, `expected 200 on ship, got ${res.status}`)
        const after = await http.get(`/orders/${ctx.order1Corr}`, { token: ctx.userToken })
        assert(after.body.data.status === 'SHIPPED', `expected SHIPPED, got ${after.body.data.status}`)
        assert(after.body.data.trackingNumber === trackNo,
          `expected trackingNumber ${trackNo}, got ${after.body.data.trackingNumber}`)
      },
    },
    {
      name: 'UC006 user confirm-receipt → SHIPPED→DELIVERED',
      run: async (ctx) => {
        const res = await http.post(`/orders/${ctx.order1Corr}/confirm-receipt`, {},
          { token: ctx.userToken })
        assert(res.status === 200, `expected 200 on confirm-receipt, got ${res.status}`)
        const after = await http.get(`/orders/${ctx.order1Corr}`, { token: ctx.userToken })
        assert(after.body.data.status === 'DELIVERED',
          `expected DELIVERED, got ${after.body.data.status}`)
      },
    },
    {
      name: 'UC007 cancel an unpaid order → CANCELLED',
      run: async (ctx) => {
        const { corrId } = await checkoutAndPoll(ctx, ctx.prod2.productId)
        ctx.order2Corr = corrId
        // Do NOT pay — cancel directly from PENDING.
        const cancel = await http.post(`/orders/${corrId}/cancel`, {}, { token: ctx.userToken })
        assert(cancel.status === 200, `expected 200 on cancel, got ${cancel.status}`)
        const after = await http.get(`/orders/${corrId}`, { token: ctx.userToken })
        assert(after.body.data.status === 'CANCELLED',
          `expected CANCELLED, got ${after.body.data.status}`)
      },
    },
    {
      name: 'UC008 cancel a paid order pre-ship → CANCELLED + refund APPROVED',
      run: async (ctx) => {
        const { corrId } = await checkoutAndPoll(ctx, ctx.prod3.productId)
        ctx.order3Corr = corrId
        const pay = await http.post(`/orders/${corrId}/pay`, {}, { token: ctx.userToken })
        assert(pay.status === 200, `expected 200 on pay, got ${pay.status}`)
        assert(pay.body.data.status === 'PAID', `expected PAID, got ${pay.body.data.status}`)
        const cancel = await http.post(`/orders/${corrId}/cancel-paid`, {},
          { token: ctx.userToken })
        assert(cancel.status === 200, `expected 200 on cancel-paid, got ${cancel.status}`)
        const after = await http.get(`/orders/${corrId}`, { token: ctx.userToken })
        assert(after.body.data.status === 'CANCELLED',
          `expected CANCELLED, got ${after.body.data.status}`)
        // cancelPaid sets refundStatus = APPROVED synchronously (no admin adjudication needed).
        assert(after.body.data.refundStatus === 'APPROVED',
          `expected refundStatus APPROVED, got ${after.body.data.refundStatus}`)
      },
    },
    {
      name: 'UC009 post-ship refund request → REFUND_PENDING, admin approve → REFUNDED',
      run: async (ctx) => {
        const { corrId } = await checkoutAndPoll(ctx, ctx.prod4.productId)
        ctx.order4Corr = corrId
        const pay = await http.post(`/orders/${corrId}/pay`, {}, { token: ctx.userToken })
        assert(pay.status === 200, `expected 200 on pay, got ${pay.status}`)
        assert(pay.body.data.status === 'PAID', `expected PAID, got ${pay.body.data.status}`)
        // Ship, then request a refund (SHIPPED → REFUND_PENDING).
        const got = await http.get(`/orders/${corrId}`, { token: ctx.userToken })
        const realPk = got.body.data.id
        const ship = await http.post(`/admin/orders/${realPk}/ship`,
          { trackingNumber: 'TRK-UC009-' + Date.now() }, { token: ctx.adminToken })
        assert(ship.status === 200, `expected 200 on ship, got ${ship.status}`)
        const refundReq = await http.post(`/orders/${corrId}/refund-request`,
          { reason: 'item arrived damaged' }, { token: ctx.userToken })
        assert(refundReq.status === 200, `expected 200 on refund-request, got ${refundReq.status}`)
        let after = await http.get(`/orders/${corrId}`, { token: ctx.userToken })
        assert(after.body.data.status === 'REFUND_PENDING',
          `expected REFUND_PENDING, got ${after.body.data.status}`)
        assert(after.body.data.refundStatus === 'PENDING',
          `expected refundStatus PENDING, got ${after.body.data.refundStatus}`)
        // Admin approves the refund (REFUND_PENDING → REFUNDED, refundStatus APPROVED).
        const approve = await http.post(`/admin/refunds/${realPk}/approve`, {},
          { token: ctx.adminToken })
        assert(approve.status === 200, `expected 200 on approve, got ${approve.status}`)
        after = await http.get(`/orders/${corrId}`, { token: ctx.userToken })
        assert(after.body.data.status === 'REFUNDED',
          `expected REFUNDED, got ${after.body.data.status}`)
        assert(after.body.data.refundStatus === 'APPROVED',
          `expected refundStatus APPROVED, got ${after.body.data.refundStatus}`)
      },
    },
    {
      name: 'guard: confirm-receipt on a PENDING order → 409',
      run: async (ctx) => {
        // Checkout a fresh PENDING order (don't pay). confirm-receipt requires SHIPPED.
        const { corrId } = await checkoutAndPoll(ctx, ctx.prod5.productId)
        const res = await http.post(`/orders/${corrId}/confirm-receipt`, {},
          { token: ctx.userToken })
        assert(res.status === 409, `expected 409 (illegal transition), got ${res.status}`)
        // Clean up: cancel it so it doesn't linger PENDING.
        await http.post(`/orders/${corrId}/cancel`, {}, { token: ctx.userToken })
      },
    },
    {
      name: 'guard: ship a non-PAID order → 409',
      run: async (ctx) => {
        // Checkout a fresh PENDING order (not paid). ship requires PAID.
        const { corrId, realPk } = await checkoutAndPoll(ctx, ctx.prod6.productId)
        const res = await http.post(`/admin/orders/${realPk}/ship`,
          { trackingNumber: 'TRK-SHOULD-FAIL' }, { token: ctx.adminToken })
        assert(res.status === 409, `expected 409 (ship non-PAID), got ${res.status}`)
        // Clean up.
        await http.post(`/orders/${corrId}/cancel`, {}, { token: ctx.userToken })
      },
    },
    {
      name: 'cleanup: delete the order-flow test products',
      run: async (ctx) => {
        for (const pid of ctx.ordersProductIds || []) {
          await http.del(`/products/${pid}`, { token: ctx.adminToken })
        }
      },
    },
  ],
}
