import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ClipboardList, RefreshCw, ChevronDown, ChevronUp, X } from 'lucide-react'
import {
  getMyOrders,
  cancelOrder,
  cancelPaid,
  requestRefund,
  confirmReceipt,
  getOrderItems,
  payOrder,
} from '@/api/order'
import type { Order, OrderItem, OrderStatus } from '@/api/types'
import { formatPrice } from './Products'

const STATUS_COLORS: Record<OrderStatus, string> = {
  PENDING: 'bg-amber-100 text-amber-700',
  PAID: 'bg-blue-100 text-blue-700',
  SHIPPED: 'bg-indigo-100 text-indigo-700',
  DELIVERED: 'bg-green-100 text-green-700',
  COMPLETED: 'bg-slate-100 text-slate-600',
  REFUND_PENDING: 'bg-orange-100 text-orange-700',
  REFUNDED: 'bg-red-100 text-red-700',
  CANCELLED: 'bg-slate-100 text-slate-600',
  FAILED: 'bg-red-100 text-red-700',
}

/** Tab label → status filter passed to getMyOrders. undefined = all statuses. */
const TABS: { label: string; status?: OrderStatus; disabled?: boolean }[] = [
  { label: '全部' },
  { label: '未付款', status: 'PENDING' },
  { label: '未发货', status: 'PAID' },
  { label: '未签收', status: 'SHIPPED' },
  { label: '已完成', status: 'COMPLETED' },
  { label: '未评价 (Phase D)', disabled: true },
]

/**
 * My orders: tabbed view with per-status actions, a refund-reason modal, and an
 * expandable line-items drawer. `activeStatus` drives getMyOrders' status filter.
 */
export default function Orders() {
  const navigate = useNavigate()
  const [rows, setRows] = useState<Order[]>([])
  const [total, setTotal] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [activeStatus, setActiveStatus] = useState<OrderStatus | undefined>(undefined)
  const [busyId, setBusyId] = useState<number | null>(null)

  // Expandable line-items drawer.
  const [expandedId, setExpandedId] = useState<number | null>(null)
  const [items, setItems] = useState<OrderItem[]>([])
  const [itemsLoading, setItemsLoading] = useState(false)

  // Refund-reason modal.
  const [refundOrderId, setRefundOrderId] = useState<number | null>(null)
  const [refundReason, setRefundReason] = useState('')

  async function load() {
    setLoading(true)
    setError(null)
    try {
      const res = await getMyOrders(1, 50, activeStatus)
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeStatus])

  async function handlePay(id: number) {
    setBusyId(id)
    try {
      await payOrder(id)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'pay failed')
    } finally {
      setBusyId(null)
    }
  }

  async function handleCancel(id: number) {
    setBusyId(id)
    try {
      await cancelOrder(id)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'cancel failed')
    } finally {
      setBusyId(null)
    }
  }

  async function handleCancelPaid(id: number) {
    setBusyId(id)
    try {
      await cancelPaid(id)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'refund failed')
    } finally {
      setBusyId(null)
    }
  }

  async function handleConfirmReceipt(id: number) {
    setBusyId(id)
    try {
      await confirmReceipt(id)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'confirm failed')
    } finally {
      setBusyId(null)
    }
  }

  function openRefundModal(id: number) {
    setRefundOrderId(id)
    setRefundReason('')
  }

  function closeRefundModal() {
    setRefundOrderId(null)
    setRefundReason('')
  }

  async function submitRefund() {
    if (refundOrderId == null) return
    setBusyId(refundOrderId)
    try {
      await requestRefund(refundOrderId, refundReason)
      closeRefundModal()
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'refund request failed')
    } finally {
      setBusyId(null)
    }
  }

  async function toggleExpand(id: number) {
    if (expandedId === id) {
      setExpandedId(null)
      return
    }
    setExpandedId(id)
    setItemsLoading(true)
    try {
      const res = await getOrderItems(id)
      setItems(res)
    } catch {
      setItems([])
    } finally {
      setItemsLoading(false)
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

      {/* Tabs */}
      <div className="flex items-center gap-1 border-b border-slate-200">
        {TABS.map((tab) => {
          const active = tab.status === activeStatus
          return (
            <button
              key={tab.label}
              disabled={tab.disabled}
              onClick={() => setActiveStatus(tab.status)}
              className={[
                'px-3 py-2 text-sm border-b-2 -mb-px transition-colors',
                tab.disabled
                  ? 'text-slate-300 cursor-not-allowed border-transparent'
                  : active
                    ? 'border-brand-600 text-brand-600 font-medium'
                    : 'border-transparent text-slate-600 hover:text-slate-800',
              ].join(' ')}
            >
              {tab.label}
            </button>
          )
        })}
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
                <>
                  <tr key={o.id} className="border-t border-slate-100">
                    <td className="px-4 py-2 text-slate-500">
                      <button
                        onClick={() => toggleExpand(o.id)}
                        className="flex items-center gap-1 text-slate-500 hover:text-slate-800"
                      >
                        {expandedId === o.id ? (
                          <ChevronUp className="w-4 h-4" />
                        ) : (
                          <ChevronDown className="w-4 h-4" />
                        )}
                        #{o.id}
                      </button>
                    </td>
                    <td className="px-4 py-2 text-slate-800">{o.customerName}</td>
                    <td className="px-4 py-2 text-right text-slate-700">
                      {formatPrice(o.totalInCents)}
                    </td>
                    <td className="px-4 py-2">
                      {o.status === 'REFUND_PENDING' ? (
                        <span className="px-2 py-0.5 rounded text-xs font-medium bg-orange-100 text-orange-700">
                          退款处理中
                        </span>
                      ) : (
                        <span
                          className={`px-2 py-0.5 rounded text-xs font-medium ${STATUS_COLORS[o.status]}`}
                        >
                          {o.status}
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-2 text-slate-500">
                      {o.createTime ? new Date(o.createTime).toLocaleString() : '—'}
                    </td>
                    <td className="px-4 py-2 text-right space-x-2">
                      <button
                        onClick={() => toggleExpand(o.id)}
                        className="text-xs text-slate-600 hover:underline"
                      >
                        详情
                      </button>
                      {o.status === 'PENDING' && (
                        <>
                          <button
                            onClick={() => handlePay(o.id)}
                            disabled={busyId === o.id}
                            className="text-xs text-blue-600 hover:underline disabled:opacity-50"
                          >
                            {busyId === o.id ? '处理中…' : '付款'}
                          </button>
                          <button
                            onClick={() => handleCancel(o.id)}
                            disabled={busyId === o.id}
                            className="text-xs text-red-600 hover:underline disabled:opacity-50"
                          >
                            取消
                          </button>
                        </>
                      )}
                      {o.status === 'PAID' && (
                        <button
                          onClick={() => handleCancelPaid(o.id)}
                          disabled={busyId === o.id}
                          className="text-xs text-red-600 hover:underline disabled:opacity-50"
                        >
                          {busyId === o.id ? '处理中…' : '退款'}
                        </button>
                      )}
                      {o.status === 'SHIPPED' && (
                        <>
                          <button
                            onClick={() => handleConfirmReceipt(o.id)}
                            disabled={busyId === o.id}
                            className="text-xs text-green-600 hover:underline disabled:opacity-50"
                          >
                            {busyId === o.id ? '处理中…' : '确认收货'}
                          </button>
                          <button
                            onClick={() => openRefundModal(o.id)}
                            disabled={busyId === o.id}
                            className="text-xs text-orange-600 hover:underline disabled:opacity-50"
                          >
                            申请退款
                          </button>
                        </>
                      )}
                      {o.status !== 'PENDING' &&
                        o.status !== 'PAID' &&
                        o.status !== 'SHIPPED' &&
                        o.status !== 'REFUND_PENDING' && (
                          <span className="text-xs text-slate-300">—</span>
                        )}
                    </td>
                  </tr>
                  {expandedId === o.id && (
                    <tr className="bg-slate-50">
                      <td colSpan={6} className="px-4 py-3">
                        {itemsLoading ? (
                          <p className="text-sm text-slate-500">Loading items…</p>
                        ) : items.length === 0 ? (
                          <p className="text-sm text-slate-400">No items.</p>
                        ) : (
                          <div className="space-y-2">
                            {o.trackingNumber &&
                              (o.status === 'SHIPPED' ||
                                o.status === 'DELIVERED' ||
                                o.status === 'COMPLETED') && (
                                <p className="text-sm text-slate-600">
                                  Tracking: <span className="font-mono">{o.trackingNumber}</span>
                                </p>
                              )}
                            <ul className="divide-y divide-slate-200">
                              {items.map((it) => (
                                <li
                                  key={it.id}
                                  className="flex items-center justify-between py-1.5"
                                >
                                  <span className="text-slate-700">{it.name}</span>
                                  <span className="text-slate-500">×{it.quantity}</span>
                                  <span className="text-slate-700">
                                    {formatPrice(it.priceInCents)}
                                  </span>
                                </li>
                              ))}
                            </ul>
                          </div>
                        )}
                      </td>
                    </tr>
                  )}
                </>
              ))}
              {rows.length === 0 && (
                <tr>
                  <td colSpan={6} className="px-4 py-6 text-center text-slate-400">
                    No orders yet.{' '}
                    <button
                      onClick={() => navigate('/products')}
                      className="text-brand-600 hover:underline"
                    >
                      Start shopping
                    </button>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}

      {/* Refund-reason modal */}
      {refundOrderId != null && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-white rounded-lg shadow-lg w-full max-w-md p-4 space-y-3">
            <div className="flex items-center justify-between">
              <h2 className="text-lg font-semibold text-slate-800">申请退款</h2>
              <button onClick={closeRefundModal} className="text-slate-400 hover:text-slate-600">
                <X className="w-5 h-5" />
              </button>
            </div>
            <p className="text-sm text-slate-500">订单 #{refundOrderId}</p>
            <textarea
              value={refundReason}
              onChange={(e) => setRefundReason(e.target.value)}
              placeholder="请输入退款原因 / refund reason"
              className="w-full border border-slate-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-brand-500"
              rows={3}
            />
            <div className="flex justify-end gap-2">
              <button
                onClick={closeRefundModal}
                className="px-3 py-1.5 rounded-md text-sm text-slate-600 hover:bg-slate-100"
              >
                取消
              </button>
              <button
                onClick={submitRefund}
                disabled={busyId === refundOrderId}
                className="px-3 py-1.5 rounded-md text-sm bg-brand-600 text-white hover:bg-brand-700 disabled:opacity-50"
              >
                {busyId === refundOrderId ? '提交中…' : '提交'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
