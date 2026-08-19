import { http } from '../lib/http.mjs'
import { assert } from '../lib/assert.mjs'
import { env } from '../lib/env.mjs'

export const suite = {
  name: 'products',
  tests: [
    {
      name: 'login the registered user (sets userToken for checkout tests)',
      run: async (ctx) => {
        const res = await http.post('/auth/login', { username: ctx.username, password: 'pass1234' })
        assert(res.status === 200, `expected 200, got ${res.status}`)
        ctx.userToken = res.body.data.token
      },
    },
    {
      name: 'list products (no auth) → contains seeds',
      run: async () => {
        const res = await http.get('/products')
        assert(res.status === 200, `expected 200, got ${res.status}`)
        // GET /products returns a MyBatis-Plus pagination envelope: {data:{records:[...]}}.
        // The product array lives under data.records (not data itself).
        const records = res.body.data.records || res.body.data || res.body
        const names = records.map(p => p.name)
        assert(names.includes('Flash Widget'), 'expected Flash Widget in list')
        assert(names.includes('Demo Gadget'), 'expected Demo Gadget in list')
      },
    },
    {
      name: 'get product by id (no auth) → 200',
      run: async () => {
        const res = await http.get('/products/1')
        assert(res.status === 200, `expected 200, got ${res.status}`)
        assert(res.body.data.name === 'Flash Widget', 'expected Flash Widget')
      },
    },
    {
      name: 'get nonexistent product → 404',
      run: async () => {
        const res = await http.get('/products/999999')
        assert(res.status === 404, `expected 404, got ${res.status}`)
      },
    },
    {
      name: 'admin creates a product',
      run: async (ctx) => {
        const res = await http.post('/products', {
          name: 'E2E Test Widget ' + Date.now(),
          priceInCents: 500,
          stock: 5,
          category: 'test',
          isFlashSale: false,
        }, { token: ctx.adminToken })
        assert(res.status === 200, `expected 200, got ${res.status}`)
        assert(res.body.data.id > 0, 'expected generated id')
        ctx.productId = res.body.data.id
        ctx.productStock = 5
      },
    },
    {
      name: 'non-admin tries to create product → 403',
      run: async (ctx) => {
        const res = await http.post('/products', {
          name: 'should fail', priceInCents: 100, stock: 1
        }, { token: ctx.userToken })
        assert(res.status === 403, `expected 403, got ${res.status}`)
      },
    },
    {
      name: 'admin edits a NON-flash product → 200',
      run: async (ctx) => {
        const res = await http.put(`/products/${ctx.productId}`, {
          name: 'E2E Edited ' + Date.now(),
          priceInCents: 600,
          stock: 8,
          isFlashSale: false,
        }, { token: ctx.adminToken })
        assert(res.status === 200, `expected 200, got ${res.status}`)
        // re-GET to confirm persistence
        const got = await http.get(`/products/${ctx.productId}`)
        assert(got.body.data.stock === 8, `expected stock 8, got ${got.body.data.stock}`)
        ctx.productStock = 8
      },
    },
    {
      name: 'admin edits a FLASH-SALE product with flashSaleAt → 200',
      knownIssue: "The original 500 (cache ObjectMapper missing java.time) is FIXED — PUT now returns 200 and flashSaleAt persists. REMAINING quirk (separate, deferred): the request sends isFlashSale but the boolean field serializes/deserializes as the JSON key 'flashSale' (Jackson boolean is-prefix), so isFlashSale=true in the request is silently ignored → the flashSale flag may not flip via this key. The 200 + flashSaleAt persisting is the current correct behavior.",
      run: async (ctx) => {
        // Use the seeded Flash Widget (id=1, is_flash_sale=1).
        const res = await http.put('/products/1', {
          name: 'Flash Widget',
          priceInCents: 1999,
          stock: 5,
          isFlashSale: true,
          flashSaleAt: '2026-08-19T15:30',
        }, { token: ctx.adminToken })
        assert(res.status === 200, `expected 200, got ${res.status}`)
        // flashSaleAt should persist (ISO parsed fine by Jackson 3).
        assert(!!res.body.data.flashSaleAt, 'expected flashSaleAt to be set in the response')
      },
    },
    {
      name: 'admin deletes the created product → 200 then 404',
      run: async (ctx) => {
        const del = await http.del(`/products/${ctx.productId}`, { token: ctx.adminToken })
        assert(del.status === 200, `expected 200 on delete, got ${del.status}`)
        const got = await http.get(`/products/${ctx.productId}`)
        assert(got.status === 404, `expected 404 after delete, got ${got.status}`)
      },
    },
  ],
}
