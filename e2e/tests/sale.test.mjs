import { http } from '../lib/http.mjs'
import { assert } from '../lib/assert.mjs'

// These tests need a flash-sale product with stock. They create one fresh
// (admin token), since products.test.mjs deletes its product.
export const suite = {
  name: 'sale',
  tests: [
    {
      name: 'create a flash-sale product for sale/checkout tests',
      run: async (ctx) => {
        const res = await http.post('/products', {
          name: 'E2E Flash Widget ' + Date.now(),
          priceInCents: 999,
          stock: 5,
          category: 'test',
          isFlashSale: true,
        }, { token: ctx.adminToken })
        assert(res.status === 200, `expected 200, got ${res.status}`)
        ctx.saleProductId = res.body.data.id
        ctx.saleStock = 5
      },
    },
    {
      name: 'checkout before sale open → documented behavior: 503 stock not initialized',
      knownIssue: 'Fail-closed by design — Redis stock:{id} key missing means the sale was never opened. NOT a bug, but confusing UX. This test pins the intended behavior.',
      run: async (ctx) => {
        const res = await http.post('/checkout', {
          items: [{ productId: ctx.saleProductId, quantity: 1 }],
          customerName: 't', customerEmail: 't@t', shippingAddress: 't',
          customerPhone: '13800000000',
          paymentMethod: 'WECHAT',
          idempotencyKey: 'pre-open-' + Date.now(),
        }, { token: ctx.userToken })
        assert(res.status === 503, `expected 503 (stock not initialized), got ${res.status}`)
        assert((res.body.message || '').includes('stock'), `expected "stock" in message, got "${res.body.message}"`)
      },
    },
    {
      name: 'admin opens the flash sale → 200',
      run: async (ctx) => {
        const res = await http.post(`/admin/sales/${ctx.saleProductId}`, {}, { token: ctx.adminToken })
        assert(res.status === 200, `expected 200, got ${res.status}`)
      },
    },
  ],
}
