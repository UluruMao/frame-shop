import { useEffect, useState } from 'react'
import { Package } from 'lucide-react'
import { pageProducts } from '@/api/product'
import type { Product } from '@/api/types'

export default function Products() {
  const [rows, setRows] = useState<Product[]>([])
  const [total, setTotal] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

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
  }, [])

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <Package className="w-5 h-5 text-brand-600" />
        <h1 className="text-xl font-semibold text-slate-800">Products</h1>
        <span className="text-sm text-slate-400">({total})</span>
      </div>

      {loading && <p className="text-sm text-slate-500">Loading…</p>}
      {error && <p className="text-sm text-red-600">{error}</p>}

      {!loading && !error && (
        <div className="bg-white border border-slate-200 rounded-lg overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-slate-50 text-slate-600">
              <tr>
                <th className="text-left px-4 py-2 font-medium">ID</th>
                <th className="text-left px-4 py-2 font-medium">Name</th>
                <th className="text-right px-4 py-2 font-medium">Price</th>
                <th className="text-right px-4 py-2 font-medium">Stock</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((p) => (
                <tr key={p.id} className="border-t border-slate-100">
                  <td className="px-4 py-2 text-slate-500">{p.id}</td>
                  <td className="px-4 py-2 text-slate-800">{p.name}</td>
                  <td className="px-4 py-2 text-right text-slate-700">${p.price.toFixed(2)}</td>
                  <td className="px-4 py-2 text-right text-slate-700">{p.stock}</td>
                </tr>
              ))}
              {rows.length === 0 && (
                <tr>
                  <td colSpan={4} className="px-4 py-6 text-center text-slate-400">
                    No products yet.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
