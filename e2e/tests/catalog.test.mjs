import { http } from '../lib/http.mjs'
import { assert } from '../lib/assert.mjs'
import { env } from '../lib/env.mjs'

// UC000 — catalog search/sort/browse (guest, no token).
// These tests complement products.test.mjs, which already covers:
//   - list products (no auth) → contains seeds
//   - get product by id (no auth) → 200
//   - get nonexistent → 404
// This suite fills the UC000 gaps: keyword search, special-char safety,
// sort by price/sales/rating, category filter + categories list, empty result.
export const suite = {
  name: 'catalog',
  tests: [
    {
      name: 'keyword search returns only matching products',
      run: async () => {
        const res = await http.get('/products?keyword=Widget')
        assert(res.status === 200, `expected 200, got ${res.status}`)
        const records = res.body.data.records || []
        assert(Array.isArray(records), 'records should be an array')
        // Every returned record name must contain "widget" (case-insensitive).
        for (const p of records) {
          assert(
            p.name.toLowerCase().includes('widget'),
            `keyword leak: "${p.name}" does not contain "widget"`
          )
        }
      },
    },
    {
      name: 'special-char keyword does not crash (SQL-injection safety)',
      run: async () => {
        // URL-encode a malicious-looking keyword; the backend parameterizes it,
        // so it must return 200 + an array (possibly empty) rather than 500.
        const evil = encodeURIComponent("<script>'%;--")
        const res = await http.get(`/products?keyword=${evil}`)
        assert(res.status === 200, `expected 200, got ${res.status}`)
        assert(Array.isArray(res.body.data.records), 'records should be an array')
      },
    },
    {
      name: 'sort by price asc then desc',
      run: async () => {
        const asc = await http.get('/products?sortField=price&sortOrder=asc&size=100')
        const desc = await http.get('/products?sortField=price&sortOrder=desc&size=100')
        assert(asc.status === 200, `asc expected 200, got ${asc.status}`)
        assert(desc.status === 200, `desc expected 200, got ${desc.status}`)
        const a = (asc.body.data.records || []).map(p => p.priceInCents)
        const d = (desc.body.data.records || []).map(p => p.priceInCents)
        if (a.length > 1) {
          for (let i = 1; i < a.length; i++) {
            assert(a[i] >= a[i - 1], `asc not non-decreasing at idx ${i}: ${a[i]} < ${a[i - 1]}`)
          }
        }
        if (d.length > 1) {
          for (let i = 1; i < d.length; i++) {
            assert(d[i] <= d[i - 1], `desc not non-increasing at idx ${i}: ${d[i]} > ${d[i - 1]}`)
          }
        }
      },
    },
    {
      name: 'sort by sales desc',
      run: async () => {
        const res = await http.get('/products?sortField=sales&sortOrder=desc&size=100')
        assert(res.status === 200, `expected 200, got ${res.status}`)
        const s = (res.body.data.records || []).map(p => p.sold)
        if (s.length > 1) {
          for (let i = 1; i < s.length; i++) {
            assert(s[i] <= s[i - 1], `sales not non-increasing at idx ${i}: ${s[i]} > ${s[i - 1]}`)
          }
        }
      },
    },
    {
      name: 'sort by rating desc',
      run: async () => {
        const res = await http.get('/products?sortField=rating&sortOrder=desc&size=100')
        assert(res.status === 200, `expected 200, got ${res.status}`)
        const r = (res.body.data.records || []).map(p => p.avgRating)
        // NOTE: Phase B has no reviews yet, so all avgRating are 0 — the ordering
        // assertion trivially holds. This still proves the endpoint returns 200,
        // the field exists, and the ordering is valid.
        if (r.length > 1) {
          for (let i = 1; i < r.length; i++) {
            assert(r[i] <= r[i - 1], `rating not non-increasing at idx ${i}: ${r[i]} > ${r[i - 1]}`)
          }
        }
      },
    },
    {
      name: 'category filter + categories list',
      run: async () => {
        const cats = await http.get('/products/categories')
        assert(cats.status === 200, `categories expected 200, got ${cats.status}`)
        assert(Array.isArray(cats.body.data), 'categories data should be an array')
        if (cats.body.data.length > 0) {
          const c = cats.body.data[0]
          const res = await http.get(`/products?category=${encodeURIComponent(c)}&size=100`)
          assert(res.status === 200, `category filter expected 200, got ${res.status}`)
          const records = res.body.data.records || []
          for (const p of records) {
            assert(p.category === c, `category leak: expected "${c}", got "${p.category}"`)
          }
        }
      },
    },
    {
      name: 'empty result for non-matching keyword',
      run: async () => {
        const res = await http.get('/products?keyword=zzzznotreal')
        assert(res.status === 200, `expected 200, got ${res.status}`)
        const records = res.body.data.records || []
        assert(Array.isArray(records), 'records should be an array')
        assert(records.length === 0, `expected 0 records, got ${records.length}`)
        assert(res.body.data.total === 0, `expected total 0, got ${res.body.data.total}`)
      },
    },
  ],
}
