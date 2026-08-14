import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ClipboardList, RefreshCw } from 'lucide-react'
import { getMyOrders, cancelOrder } from '@/api/order'
import type { Order, OrderStatus } from '@/api/types'
import { formatPrice } from './Products'

const STATUS_COLORS: Record<OrderStatus, string> = {
  PENDING: 'bg-amber-100 text-amber-700',
  PAID: 'bg-green-100 text-green-700',
  CANCELLED: 'bg-slate-100 text-slate-600',
  FAILED: 'bg-red-100 text-red-700',
}

/** My orders: paginated table with cancel action for PENDING orders. */
export default function Orders() {
  const navigate = useNavigate()
  const [rows, setRows] = useState<Order[]>([])
  const [total, setTotal] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [cancellingId, setCancellingId] = useState<number | null>(null)

  async function load() {
    setLoading(true)
    setError(null)
    try {
      const res = await getMyOrders(1, 50)
      setRows(res.records)
      setTotal(res.total)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'failed to load orders')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [])

  async function handleCancel(id: number) {
    setCancellingId(id)
    try {
      await cancelOrder(id)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'cancel failed')
    } finally {
      setCancellingId(null)
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <ClipboardList className="w-5 h-5 text-brand-600" />
          <h1 className="text-xl font-semibold text-slate-800">My Orders</h1>
          <span className="text-sm text-slate-400">({total})</span>
        </div>
        <button
          onClick={load}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-slate-600 text-sm hover:bg-slate-100"
        >
          <RefreshCw className="w-4 h-4" />
          Refresh
        </button>
      </div>

      {loading && <p className="text-sm text-slate-500">Loading…</p>}
      {error && <p className="text-sm text-red-600">{error}</p>}

      {!loading && !error && (
        <div className="bg-white border border-slate-200 rounded-lg overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-slate-50 text-slate-600">
              <tr>
                <th className="text-left px-4 py-2 font-medium">Order</th>
                <th className="text-left px-4 py-2 font-medium">Customer</th>
                <th className="text-right px-4 py-2 font-medium">Total</th>
                <th className="text-left px-4 py-2 font-medium">Status</th>
                <th className="text-left px-4 py-2 font-medium">Created</th>
                <th className="text-right px-4 py-2 font-medium">Actions</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((o) => (
                <tr key={o.id} className="border-t border-slate-100">
                  <td className="px-4 py-2 text-slate-500">#{o.id}</td>
                  <td className="px-4 py-2 text-slate-800">{o.customerName}</td>
                  <td className="px-4 py-2 text-right text-slate-700">{formatPrice(o.totalInCents)}</td>
                  <td className="px-4 py-2">
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${STATUS_COLORS[o.status]}`}>
                      {o.status}
                    </span>
                  </td>
                  <td className="px-4 py-2 text-slate-500">
                    {o.createTime ? new Date(o.createTime).toLocaleString() : '—'}
                  </td>
                  <td className="px-4 py-2 text-right">
                    {o.status === 'PENDING' ? (
                      <button
                        onClick={() => handleCancel(o.id)}
                        disabled={cancellingId === o.id}
                        className="text-xs text-red-600 hover:underline disabled:opacity-50"
                      >
                        {cancellingId === o.id ? 'Cancelling…' : 'Cancel'}
                      </button>
                    ) : (
                      <span className="text-xs text-slate-300">—</span>
                    )}
                  </td>
                </tr>
              ))}
              {rows.length === 0 && (
                <tr>
                  <td colSpan={6} className="px-4 py-6 text-center text-slate-400">
                    No orders yet.{' '}
                    <button onClick={() => navigate('/products')} className="text-brand-600 hover:underline">
                      Start shopping
                    </button>
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
