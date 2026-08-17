import { useEffect, useRef, useState } from 'react'
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell } from 'recharts'
import { Package, ShoppingCart, Trash2, Zap } from 'lucide-react'
import { Link } from 'react-router-dom'
import { pageProducts, getProduct } from '@/api/product'
import type { Product } from '@/api/types'
import { useCart } from '@/store/CartContext'

/** Format integer cents as a dollar string: 1299 → "$12.99". */
export function formatPrice(priceInCents: number): string {
  return `$${(priceInCents / 100).toFixed(2)}`
}

/** Format ms as a compact countdown: "2d 03:45:12" or "03:45:12". */
function formatCountdown(ms: number): string {
  const totalSec = Math.floor(ms / 1000)
  const days = Math.floor(totalSec / 86400)
  const hours = Math.floor((totalSec % 86400) / 3600)
  const mins = Math.floor((totalSec % 3600) / 60)
  const secs = totalSec % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  const hms = `${pad(hours)}:${pad(mins)}:${pad(secs)}`
  return days > 0 ? `${days}d ${hms}` : hms
}

/**
 * Browse products + add to cart. Public reads (GET /products) don't require auth,
 * but adding to cart implies the user will check out (which does). Flash-sale
 * items show a badge. Admin product CRUD lives on the Admin page, not here.
 *
 * Flash-sale items with a future `flashSaleAt` show a live countdown timer to
 * sale open. Once `flashSaleAt` has passed (sale is live), a recharts BarChart
 * shows the remaining stock, polled from the product detail endpoint every 4s.
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
          {rows.map((p) => (
            <FlashSaleCard
              key={p.id}
              product={p}
              inCart={items.find((i) => i.productId === p.id)}
              onAdd={() => add(p)}
              onRemove={() => remove(p.id)}
            />
          ))}
          {rows.length === 0 && (
            <p className="col-span-full text-center text-slate-400 py-6">No products yet.</p>
          )}
        </div>
      )}
    </div>
  )
}

interface FlashSaleCardProps {
  product: Product
  inCart: { quantity: number } | undefined
  onAdd: () => void
  onRemove: () => void
}

/**
 * A product card with time-gated flash-sale behavior:
 * - If `flashSaleAt` is in the future → show a countdown timer to sale open.
 * - If `flashSaleAt` has passed → sale is live; poll product detail every 4s and
 *   show a recharts BarChart of the remaining stock.
 */
function FlashSaleCard({ product: initial, inCart, onAdd, onRemove }: FlashSaleCardProps) {
  const [now, setNow] = useState(Date.now())
  const [liveStock, setLiveStock] = useState<number | null>(null)
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)

  const saleOpensAt = initial.flashSaleAt ? new Date(initial.flashSaleAt).getTime() : 0
  const isScheduled = initial.isFlashSale && saleOpensAt > 0
  const saleLive = isScheduled && now >= saleOpensAt
  const remaining = saleOpensAt - now

  // Tick every second for the countdown while the sale hasn't opened.
  useEffect(() => {
    if (!isScheduled || saleLive) return
    const tick = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(tick)
  }, [isScheduled, saleLive])

  // Once the sale is live, poll the product detail endpoint every 4s for stock.
  useEffect(() => {
    if (!saleLive) return
    let cancelled = false
    async function poll() {
      try {
        const p = await getProduct(initial.id)
        if (!cancelled) setLiveStock(p.stock)
      } catch {
        // Network errors don't crash the UI — keep the last known stock.
      }
    }
    void poll()
    pollRef.current = setInterval(poll, 4000)
    return () => {
      cancelled = true
      if (pollRef.current) clearInterval(pollRef.current)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [saleLive, initial.id])

  const displayStock = liveStock ?? initial.stock
  const soldOut = (displayStock ?? 0) <= 0
  const stockPct = initial.stock > 0 ? Math.max(0, Math.min(100, (displayStock / initial.stock) * 100)) : 0
  const stockColor = stockPct > 50 ? '#16a34a' : stockPct > 20 ? '#d97706' : '#dc2626'

  return (
    <div className="bg-white border border-slate-200 rounded-lg p-4 flex flex-col gap-3">
      <div className="flex items-start justify-between gap-2">
        <h3 className="text-sm font-medium text-slate-800">{initial.name}</h3>
        {initial.isFlashSale && (
          <span className="flex items-center gap-0.5 px-1.5 py-0.5 rounded bg-amber-100 text-amber-700 text-xs font-medium">
            <Zap className="w-3 h-3" />
            Flash
          </span>
        )}
      </div>
      {initial.description && <p className="text-xs text-slate-500 line-clamp-2">{initial.description}</p>}

      {/* Countdown timer — sale scheduled but not yet open */}
      {isScheduled && !saleLive && remaining > 0 && (
        <div className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-md bg-amber-50 border border-amber-200">
          <Zap className="w-3.5 h-3.5 text-amber-600" />
          <span className="text-xs text-amber-700">Sale opens in</span>
          <span className="text-xs font-mono font-semibold text-amber-800">{formatCountdown(remaining)}</span>
        </div>
      )}

      {/* Live stock bar chart — sale is open */}
      {saleLive && (
        <div className="border border-slate-200 rounded-md p-2">
          <div className="flex items-center justify-between mb-1">
            <span className="text-xs font-medium text-slate-600">Live stock</span>
            <span className="text-xs text-slate-500">{displayStock} / {initial.stock}</span>
          </div>
          <div className="h-24">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={[{ name: initial.name, stock: displayStock }]} layout="vertical">
                <XAxis type="number" hide domain={[0, initial.stock]} />
                <YAxis type="category" dataKey="name" hide />
                <Tooltip
                  cursor={{ fill: 'transparent' }}
                  formatter={(value: number) => [`${value} left`, 'Stock']}
                />
                <Bar dataKey="stock" radius={[0, 4, 4, 0]}>
                  <Cell fill={stockColor} />
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>
        </div>
      )}

      <div className="flex items-center justify-between mt-auto">
        <span className="text-sm text-slate-700">{formatPrice(initial.priceInCents)}</span>
        {!saleLive && <span className="text-xs text-slate-400">Stock: {initial.stock}</span>}
      </div>
      <div className="flex items-center gap-2">
        <button
          type="button"
          disabled={soldOut}
          onClick={onAdd}
          className="flex-1 flex items-center justify-center gap-1.5 py-1.5 rounded-md bg-brand-600 text-white text-xs font-medium hover:bg-brand-700 disabled:opacity-50"
        >
          <ShoppingCart className="w-3.5 h-3.5" />
          {soldOut ? 'Sold out' : inCart ? `In cart (${inCart.quantity})` : 'Add to cart'}
        </button>
        {inCart && (
          <button
            type="button"
            onClick={onRemove}
            className="p-1.5 rounded-md text-slate-400 hover:text-red-600 hover:bg-red-50"
            title="Remove from cart"
          >
            <Trash2 className="w-3.5 h-3.5" />
          </button>
        )}
      </div>
    </div>
  )
}
