import { http } from '../lib/http.mjs'
import { assert } from '../lib/assert.mjs'

// The headline test: N concurrent buyers, stock=K. Exactly K succeed, the rest
// get sold-out (409) — never oversell. Run against the real deployed system.
export const suite = {
  name: 'concurrency',
  tests: [
    {
      name: '50 concurrent buyers, stock=5 → exactly 5 succeed, no oversell',
      run: async (ctx) => {
        // 1. Create a fresh flash-sale product with stock 5 + open the sale.
        const created = await http.post('/products', {
          name: 'E2E Mutex Widget ' + Date.now(),
          priceInCents: 100,
          stock: 5,
          isFlashSale: true,
        }, { token: ctx.adminToken })
        assert(created.status === 200, `product create failed: ${created.status}`)
        const pid = created.body.data.id
        const open = await http.post(`/admin/sales/${pid}`, {}, { token: ctx.adminToken })
        assert(open.status === 200, `sale open failed: ${open.status}`)

        // 2. Register 50 distinct buyers (each needs a unique username + token).
        const N = 50, K = 5
        const buyers = []
        for (let i = 0; i < N; i++) {
          const u = 'e2e_mutex_' + Date.now() + '_' + i
          await http.post('/auth/register', { username: u, password: 'pass1234' })
          const login = await http.post('/auth/login', { username: u, password: 'pass1234' })
          buyers.push(login.body.data.token)
        }

        // 3. Fire all 50 checkouts simultaneously.
        const results = await Promise.all(buyers.map((token, i) =>
          http.post('/checkout', {
            items: [{ productId: pid, quantity: 1 }],
            customerName: 't', customerEmail: 't@t', shippingAddress: 't',
            idempotencyKey: `mutex-${Date.now()}-${i}`,
          }, { token })
        ))

        // 4. Count outcomes.
        const accepted = results.filter(r => r.status === 202).length
        const soldOut = results.filter(r => r.status === 409).length

        // 5. THE ASSERTION: exactly K succeeded, never more (no oversell).
        assert(accepted === K, `expected exactly ${K} accepted, got ${accepted} — OVERSELL RISK if > ${K}`)
        // The rest are sold-out (409) or 503 (race on stock key). Allow 409 or 503 for the failures.
        assert(accepted + soldOut === N || accepted + results.filter(r => r.status === 503).length === N,
          `expected all ${N} accounted for, got accepted=${accepted} soldOut=${soldOut}`)

        // 6. Final product stock = 0 (all K claimed by the accepted checkouts).
        const finalProd = await http.get(`/products/${pid}`)
        assert(finalProd.body.data.stock === 0, `expected final stock 0, got ${finalProd.body.data.stock}`)

        // 7. Cleanup.
        await http.del(`/products/${pid}`, { token: ctx.adminToken })
      },
    },
  ],
}
