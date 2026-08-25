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
import { listUnreviewedOrders, listOrderReviews } from '@/api/review'
import ReviewModal from '@/components/ReviewModal'
import Button from '@/components/ui/Button'
import PageHeader from '@/components/ui/PageHeader'
import StatusBadge from '@/components/ui/StatusBadge'
import { Textarea } from '@/components/ui/Input'
import { formatPrice } from '@/lib/format'
import type { Order, OrderItem, OrderStatus, Review } from '@/api/types'

/** Tab label → status filter passed to getMyOrders. undefined = all statuses. */
const TABS: { label: string; status?: OrderStatus; unreviewed?: boolean }[] = [
  { label: '全部' },
  { label: '未付款', status: 'PENDING' },
  { label: '未发货', status: 'PAID' },
  { label: '未签收', status: 'SHIPPED' },
  { label: '已完成', status: 'COMPLETED' },
  { label: '未评价', unreviewed: true },
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

  // Phase D — 未评价 tab + per-item review modal.
  const [unreviewedMode, setUnreviewedMode] = useState(false)
  const [reviewsByOrder, setReviewsByOrder] = useState<Record<number, Review[]>>({})
  const [reviewTarget, setReviewTarget] = useState<{
    productId: number
    existing: Review | null
  } | null>(null)

  async function load() {
    setLoading(true)
    setError(null)
    try {
      if (unreviewedMode) {
        const list = await listUnreviewedOrders()
        setRows(list)
        setTotal(list.length)
      } else {
        const res = await getMyOrders(1, 50, activeStatus)
        setRows(res.records)
        setTotal(res.total)
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'failed to load orders')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeStatus, unreviewedMode])

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
    // Phase D — fetch existing reviews for this order (best-effort; failures
    // just leave the drawer without review badges — the button still works).
    listOrderReviews(id)
      .then((r) => setReviewsByOrder((prev) => ({ ...prev, [id]: r })))
      .catch(() => {})
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <PageHeader
          title="My Orders"
          icon={<ClipboardList className="w-5 h-5 text-brand-600" />}
          count={total}
        />
        <Button variant="ghost" size="sm" onClick={load} icon={<RefreshCw className="w-4 h-4" />}>
          Refresh
        </Button>
      </div>

      {/* Tabs */}
      <div className="flex items-center gap-1 border-b border-slate-200">
        {TABS.map((tab) => {
          const active = unreviewedMode ? !!tab.unreviewed : tab.status === activeStatus
          return (
            <button
              key={tab.label}
              onClick={() => {
                setActiveStatus(tab.status)
                setUnreviewedMode(!!tab.unreviewed)
              }}
              className={[
                'px-3 py-2 text-sm border-b-2 -mb-px transition-colors',
                active
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
                        <StatusBadge status={o.status} label="退款处理中" />
                      ) : (
                        <StatusBadge status={o.status} />
                      )}
                    </td>
                    <td className="px-4 py-2 text-slate-500">
                      {o.createTime ? new Date(o.createTime).toLocaleString() : '—'}
                    </td>
                    <td className="px-4 py-2 text-right space-x-2">
                      <Button variant="ghost" size="sm" onClick={() => toggleExpand(o.id)}>
                        详情
                      </Button>
                      {o.status === 'PENDING' && (
                        <>
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => handlePay(o.id)}
                            disabled={busyId === o.id}
                          >
                            {busyId === o.id ? '处理中…' : '付款'}
                          </Button>
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => handleCancel(o.id)}
                            disabled={busyId === o.id}
                          >
                            取消
                          </Button>
                        </>
                      )}
                      {o.status === 'PAID' && (
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => handleCancelPaid(o.id)}
                          disabled={busyId === o.id}
                        >
                          {busyId === o.id ? '处理中…' : '退款'}
                        </Button>
                      )}
                      {o.status === 'SHIPPED' && (
                        <>
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => handleConfirmReceipt(o.id)}
                            disabled={busyId === o.id}
                          >
                            {busyId === o.id ? '处理中…' : '确认收货'}
                          </Button>
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => openRefundModal(o.id)}
                            disabled={busyId === o.id}
                          >
                            申请退款
                          </Button>
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
                                  {(o.status === 'DELIVERED' ||
                                    o.status === 'COMPLETED') && (
                                    <button
                                      onClick={() =>
                                        setReviewTarget({
                                          productId: it.productId,
                                          existing:
                                            (reviewsByOrder[o.id] ?? []).find(
                                              (r) => r.productId === it.productId,
                                            ) ?? null,
                                        })
                                      }
                                      className="text-xs text-brand-600 hover:underline"
                                    >
                                      {(reviewsByOrder[o.id] ?? []).some(
                                        (r) => r.productId === it.productId,
                                      )
                                        ? '编辑评价'
                                        : '写评价'}
                                    </button>
                                  )}
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
                    <Button variant="ghost" size="sm" onClick={() => navigate('/products')}>
                      Start shopping
                    </Button>
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
            <Textarea
              value={refundReason}
              onChange={(e) => setRefundReason(e.target.value)}
              placeholder="请输入退款原因 / refund reason"
              rows={3}
            />
            <div className="flex justify-end gap-2">
              <Button variant="ghost" size="sm" onClick={closeRefundModal}>
                取消
              </Button>
              <Button
                onClick={submitRefund}
                disabled={busyId === refundOrderId}
              >
                {busyId === refundOrderId ? '提交中…' : '提交'}
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* Phase D — review modal for a line item */}
      {reviewTarget && expandedId && (
        <ReviewModal
          orderId={expandedId}
          productId={reviewTarget.productId}
          existing={reviewTarget.existing}
          onClose={() => setReviewTarget(null)}
          onSuccess={async () => {
            setReviewTarget(null)
            if (expandedId) {
              listOrderReviews(expandedId)
                .then((r) =>
                  setReviewsByOrder((p) => ({ ...p, [expandedId]: r })),
                )
                .catch(() => {})
            }
            await load()
          }}
        />
      )}
    </div>
  )
}
