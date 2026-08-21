import { http } from '../lib/http.mjs'
import { assert } from '../lib/assert.mjs'
import { env } from '../lib/env.mjs'

// UC010 — reviews end-to-end (Phase D).
//
// Flow: create a flash-sale product + open its sale → checkout → pay → admin ship
// (PAID→SHIPPED) → user confirm-receipt (SHIPPED→DELIVERED) → user submits a 3-D review for
// the order's line item → assert the order transitioned to COMPLETED + disappeared from the
// 未评价 list. Then edit the review (PUT /reviews/{id}) → assert edited=true.
//
// Reuses the orders suite's conventions:
//  - ctx.adminToken set by an earlier suite; ctx.userToken falls back to admin (the SMS-gated
//    /auth/register can't run in e2e — same known gap as the orders suite). /orders/** +
//    /checkout + /orders/{id}/reviews only require authentication.
//  - checkout returns a millis correlation id; pollUntilReal resolves it to the DB PK.
//  - user endpoints accept corrId; admin ship takes the real PK.
//
// Contract (verified against ReviewController + OrderController):
//  - GET  /orders/unreviewed            → Result<List<Order>>  (未评价 tab)
//  - GET  /orders/{orderId}/reviews     → Result<List<ReviewVO>>
//  - POST /orders/{orderId}/reviews     → Result<ReviewVO>  (body: ReviewCreateRequest; path orderId is authoritative)
//  - PUT  /reviews/{reviewId}           → Result<ReviewVO>  (body: ReviewUpdateRequest)
//  - The order's line item productId is needed for the review. We fetch it via
//    GET /orders/{realPk}/items (items endpoint takes corrId OR realPk; we use realPk).

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

async function createAndOpenSaleProduct(ctx, stock) {
  const res = await http.post('/products', {
    name: 'E2E Reviews ' + Date.now() + '-' + Math.random().toString(36).slice(2, 7),
    priceInCents: 1999, stock, category: 'test', isFlashSale: true,
  }, { token: ctx.adminToken })
  assert(res.status === 200, `expected 200 creating product, got ${res.status}`)
  const productId = res.body.data.id
  const open = await http.post(`/admin/sales/${productId}`, {}, { token: ctx.adminToken })
  assert(open.status === 200, `expected 200 opening sale, got ${open.status}`)
  return { productId, stock }
}

export const suite = {
  name: 'reviews',
  tests: [
    {
      name: 'setup: admin + user tokens (admin is the principal)',
      run: async (ctx) => {
        if (!ctx.adminToken) {
          const a = await http.post('/auth/login', env.ADMIN)
          assert(a.status === 200, `admin login expected 200, got ${a.status}`)
          ctx.adminToken = a.body.data.token
        }
        // Use admin as the principal (not the registered ctx.userToken) so this suite's
        // checkouts don't share the registered user's /checkout rate-limit bucket.
        ctx.userToken = ctx.adminToken
      },
    },
    {
      name: 'create + open a flash-sale product for the review flow',
      run: async (ctx) => {
        ctx.revProd = await createAndOpenSaleProduct(ctx, 5)
      },
    },
    {
      name: 'UC010: checkout → pay → ship → confirm-receipt → DELIVERED',
      run: async (ctx) => {
        const idem = 'reviews-' + Date.now() + '-' + Math.random().toString(36).slice(2, 7)
        const co = await http.post('/checkout', {
          items: [{ productId: ctx.revProd.productId, quantity: 1 }],
          customerName: 't', customerEmail: 't@t', shippingAddress: 't',
          customerPhone: '13800000000', paymentMethod: 'WECHAT', idempotencyKey: idem,
        }, { token: ctx.userToken })
        assert(co.status === 202, `expected 202 on checkout, got ${co.status}`)
        ctx.revCorrId = co.body.data.orderId
        const order = await pollUntilReal(ctx.revCorrId, ctx.userToken)
        ctx.revRealPk = order.id
        assert(order.status === 'PENDING', `expected PENDING, got ${order.status}`)

        const pay = await http.post(`/orders/${ctx.revCorrId}/pay`, {}, { token: ctx.userToken })
        assert(pay.status === 200, `expected 200 on pay, got ${pay.status}`)
        assert(pay.body.data.status === 'PAID', `expected PAID, got ${pay.body.data.status}`)

        // admin ship (takes real PK) → SHIPPED. Ship returns Result<Void> (no data), so
        // re-GET via the corrId to read the new status.
        const ship = await http.post(`/admin/orders/${ctx.revRealPk}/ship`,
          { trackingNumber: 'TRK-' + Date.now() }, { token: ctx.adminToken })
        assert(ship.status === 200, `expected 200 on ship, got ${ship.status}`)
        const shipped = await http.get(`/orders/${ctx.revCorrId}`, { token: ctx.userToken })
        assert(shipped.body.data.status === 'SHIPPED', `expected SHIPPED, got ${shipped.body.data.status}`)

        // user confirm-receipt → DELIVERED (reviewable state). Also Result<Void> → re-GET.
        const recv = await http.post(`/orders/${ctx.revCorrId}/confirm-receipt`, {}, { token: ctx.userToken })
        assert(recv.status === 200, `expected 200 on confirm-receipt, got ${recv.status}`)
        const delivered = await http.get(`/orders/${ctx.revCorrId}`, { token: ctx.userToken })
        assert(delivered.body.data.status === 'DELIVERED', `expected DELIVERED, got ${delivered.body.data.status}`)
      },
    },
    {
      name: 'UC010: DELIVERED order appears in 未评价 list',
      run: async (ctx) => {
        const unrev = await http.get('/orders/unreviewed', { token: ctx.userToken })
        assert(unrev.status === 200, `expected 200 on unreviewed, got ${unrev.status}`)
        const ids = (unrev.body.data || []).map(o => o.id)
        assert(ids.includes(ctx.revRealPk), `expected order ${ctx.revRealPk} in 未评价 list, got ${JSON.stringify(ids)}`)
      },
    },
    {
      name: 'UC010: submit 3-D review → order COMPLETED + leaves 未评价',
      run: async (ctx) => {
        // line item productId for the review
        const items = await http.get(`/orders/${ctx.revRealPk}/items`, { token: ctx.userToken })
        assert(items.status === 200, `expected 200 on items, got ${items.status}`)
        assert((items.body.data || []).length > 0, 'expected ≥1 order item')
        const productId = items.body.data[0].productId

        const review = await http.post(`/orders/${ctx.revRealPk}/reviews`, {
          orderId: ctx.revRealPk, productId,
          qualityStars: 5, logisticsStars: 4, serviceStars: 3,
          content: 'e2e great', imageUrls: [], isAnonymous: false,
        }, { token: ctx.userToken })
        assert(review.status === 200, `expected 200 on review create, got ${review.status}`)
        ctx.revReviewId = review.body.data.id
        assert(review.body.data.qualityStars === 5, `expected qualityStars 5, got ${review.body.data.qualityStars}`)

        // order now COMPLETED (last item's first review → completeOrder CAS)
        const order = await http.get(`/orders/${ctx.revCorrId}`, { token: ctx.userToken })
        assert(order.body.data.status === 'COMPLETED', `expected COMPLETED after review, got ${order.body.data.status}`)

        // no longer in 未评价
        const unrev = await http.get('/orders/unreviewed', { token: ctx.userToken })
        const ids = (unrev.body.data || []).map(o => o.id)
        assert(!ids.includes(ctx.revRealPk), `expected order ${ctx.revRealPk} GONE from 未评价, still present`)
      },
    },
    {
      name: 'UC010: edit review → edited=true',
      run: async (ctx) => {
        const upd = await http.put(`/reviews/${ctx.revReviewId}`, {
          qualityStars: 2, logisticsStars: 2, serviceStars: 2, content: 'e2e edited',
        }, { token: ctx.userToken })
        assert(upd.status === 200, `expected 200 on review edit, got ${upd.status}`)
        assert(upd.body.data.qualityStars === 2, `expected qualityStars 2 after edit, got ${upd.body.data.qualityStars}`)
        assert(upd.body.data.edited === true, `expected edited=true, got ${upd.body.data.edited}`)
      },
    },
  ],
}
