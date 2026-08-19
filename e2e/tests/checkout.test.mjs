import { http } from '../lib/http.mjs'
import { assert } from '../lib/assert.mjs'
import { env } from '../lib/env.mjs'

// Polls order_status until "pending" (the consumer drain), bounded by POLL_TIMEOUT_MS.
async function pollUntilPending(orderId, token) {
  const deadline = Date.now() + env.POLL_TIMEOUT_MS
  while (Date.now() < deadline) {
    const res = await http.get(`/orders/${orderId}`, { token })
    if (res.body.data && res.body.data.status === 'PENDING') return res.body.data
    await new Promise(r => setTimeout(r, env.POLL_INTERVAL_MS))
  }
  throw new Error(`order ${orderId} did not reach PENDING within ${env.POLL_TIMEOUT_MS}ms`)
}

export const suite = {
  name: 'checkout',
  tests: [
    {
      name: 'checkout after sale open → 202 queued',
      run: async (ctx) => {
        ctx.idemKey = 'checkout-' + Date.now()
        const res = await http.post('/checkout', {
          items: [{ productId: ctx.saleProductId, quantity: 1 }],
          customerName: 't', customerEmail: 't@t', shippingAddress: 't',
          idempotencyKey: ctx.idemKey,
        }, { token: ctx.userToken })
        assert(res.status === 202, `expected 202, got ${res.status}`)
        assert(res.body.data.status === 'queued', `expected queued, got ${res.body.data.status}`)
        ctx.orderId = res.body.data.orderId
        ctx.saleStock -= 1  // track expected stock after this sale
      },
    },
    {
      name: 'poll order status until PENDING',
      run: async (ctx) => {
        const order = await pollUntilPending(ctx.orderId, ctx.userToken)
        assert(order.status === 'PENDING', `expected PENDING, got ${order.status}`)
      },
    },
    {
      name: 'pay the order → PAID, stock decremented',
      run: async (ctx) => {
        const res = await http.post(`/orders/${ctx.orderId}/pay`, {}, { token: ctx.userToken })
        assert(res.status === 200, `expected 200, got ${res.status}`)
        assert(res.body.data.status === 'PAID', `expected PAID, got ${res.body.data.status}`)
        const got = await http.get(`/products/${ctx.saleProductId}`)
        assert(got.body.data.stock === ctx.saleStock, `expected stock ${ctx.saleStock}, got ${got.body.data.stock}`)
      },
    },
    {
      name: 'checkout + cancel a queued order → stock re-credited',
      run: async (ctx) => {
        const idem = 'cancel-' + Date.now()
        const co = await http.post('/checkout', {
          items: [{ productId: ctx.saleProductId, quantity: 1 }],
          customerName: 't', customerEmail: 't@t', shippingAddress: 't',
          idempotencyKey: idem,
        }, { token: ctx.userToken })
        assert(co.status === 202, `expected 202, got ${co.status}`)
        const orderId = co.body.data.orderId
        await pollUntilPending(orderId, ctx.userToken)
        const cancel = await http.post(`/orders/${orderId}/cancel`, {}, { token: ctx.userToken })
        assert(cancel.status === 200, `expected 200 on cancel, got ${cancel.status}`)
        assert(cancel.body.data.status === 'CANCELLED', `expected CANCELLED, got ${cancel.body.data.status}`)
        // stock should be back to ctx.saleStock (cancel re-credits)
        const got = await http.get(`/products/${ctx.saleProductId}`)
        assert(got.body.data.stock === ctx.saleStock, `expected stock ${ctx.saleStock} after cancel, got ${got.body.data.stock}`)
      },
    },
    {
      name: 'duplicate idempotency key → same order (no double)',
      run: async (ctx) => {
        const res = await http.post('/checkout', {
          items: [{ productId: ctx.saleProductId, quantity: 1 }],
          customerName: 't', customerEmail: 't@t', shippingAddress: 't',
          idempotencyKey: ctx.idemKey,  // reuse the first checkout's key
        }, { token: ctx.userToken })
        // Expect the same order (200 with existing), NOT a new 202.
        assert(res.body.data.orderId === ctx.orderId, 'expected same orderId for duplicate idempotency key')
      },
    },
    {
      name: 'checkout more than available stock → 409 sold out',
      run: async (ctx) => {
        // ctx.saleProduct has ctx.saleStock left (0 after the cancel-credited one above? track carefully).
        // Request far more than remaining.
        const res = await http.post('/checkout', {
          items: [{ productId: ctx.saleProductId, quantity: 999 }],
          customerName: 't', customerEmail: 't@t', shippingAddress: 't',
          idempotencyKey: 'soldout-' + Date.now(),
        }, { token: ctx.userToken })
        assert(res.status === 409 || res.status === 503, `expected 409 (sold out) or 503, got ${res.status}`)
      },
    },
    {
      name: 'cleanup: delete the sale test product',
      run: async (ctx) => {
        await http.del(`/products/${ctx.saleProductId}`, { token: ctx.adminToken })
        // no assertion — best-effort cleanup
      },
    },
  ],
}
