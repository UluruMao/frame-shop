import { useEffect, useState } from 'react'
import { Package, ShoppingCart, Trash2, Zap } from 'lucide-react'
import { Link } from 'react-router-dom'
import { pageProducts } from '@/api/product'
import type { Product } from '@/api/types'
import { useCart } from '@/store/CartContext'

/** Format integer cents as a dollar string: 1299 → "$12.99". */
export function formatPrice(priceInCents: number): string {
  return `$${(priceInCents / 100).toFixed(2)}`
}

/**
 * Browse products + add to cart. Public reads (GET /products) don't require auth,
 * but adding to cart implies the user will check out (which does). Flash-sale
 * items show a badge. Admin product CRUD lives on the Admin page, not here.
 */
export default function Products() {
  const [rows, setRows] = useState<Product[]>([])
  const [total, setTotal] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const { add, remove, reconcile, count, items } = useCart()

  useEffect(() => {
    let cancelled = false
    ;(async () => {
      setLoading(true)
      setError(null)
      try {
        const res = await pageProducts({ current: 1, size: 50 })
        if (cancelled) return
        setRows(res.records)
        setTotal(res.total)
        // Bring any persisted cart lines in line with current stock (drops/caps).
        reconcile(res.records)
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'failed to load products')
        }
      } finally {
        if (!cancelled) setLoading(false)
      }
    })()
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Package className="w-5 h-5 text-brand-600" />
          <h1 className="text-xl font-semibold text-slate-800">Products</h1>
          <span className="text-sm text-slate-400">({total})</span>
        </div>
        {count > 0 && (
          <Link
            to="/checkout"
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-md bg-brand-600 text-white text-sm font-medium hover:bg-brand-700"
          >
            <ShoppingCart className="w-4 h-4" />
            Cart ({count})
          </Link>
        )}
      </div>

      {loading && <p className="text-sm text-slate-500">Loading…</p>}
      {error && <p className="text-sm text-red-600">{error}</p>}

      {!loading && !error && (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {rows.map((p) => {
            const inCart = items.find((i) => i.productId === p.id)
            const soldOut = (p.stock ?? 0) <= 0
            return (
              <div
                key={p.id}
                className="bg-white border border-slate-200 rounded-lg p-4 flex flex-col gap-3"
              >
                <div className="flex items-start justify-between gap-2">
                  <h3 className="text-sm font-medium text-slate-800">{p.name}</h3>
                  {p.isFlashSale && (
                    <span className="flex items-center gap-0.5 px-1.5 py-0.5 rounded bg-amber-100 text-amber-700 text-xs font-medium">
                      <Zap className="w-3 h-3" />
                      Flash
                    </span>
                  )}
                </div>
                {p.description && <p className="text-xs text-slate-500 line-clamp-2">{p.description}</p>}
                <div className="flex items-center justify-between mt-auto">
                  <span className="text-sm text-slate-700">{formatPrice(p.priceInCents)}</span>
                  <span className="text-xs text-slate-400">Stock: {p.stock}</span>
                </div>
                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    disabled={soldOut}
                    onClick={() => add(p)}
                    className="flex-1 flex items-center justify-center gap-1.5 py-1.5 rounded-md bg-brand-600 text-white text-xs font-medium hover:bg-brand-700 disabled:opacity-50"
                  >
                    <ShoppingCart className="w-3.5 h-3.5" />
                    {soldOut ? 'Sold out' : inCart ? `In cart (${inCart.quantity})` : 'Add to cart'}
                  </button>
                  {inCart && (
                    <button
                      type="button"
                      onClick={() => remove(p.id)}
                      className="p-1.5 rounded-md text-slate-400 hover:text-red-600 hover:bg-red-50"
                      title="Remove from cart"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  )}
                </div>
              </div>
            )
          })}
          {rows.length === 0 && (
            <p className="col-span-full text-center text-slate-400 py-6">No products yet.</p>
          )}
        </div>
      )}
    </div>
  )
}
