import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ClipboardList, RefreshCw, ChevronDown, ChevronUp } from 'lucide-react'
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
import Modal from '@/components/ui/Modal'
import PageHeader from '@/components/ui/PageHeader'
import StatusBadge from '@/components/ui/StatusBadge'
import Table from '@/components/ui/Table'
import Tabs from '@/components/ui/Tabs'
import { Textarea } from '@/components/ui/Input'
import { formatPrice } from '@/lib/format'
import type { Order, OrderItem, OrderStatus, Review } from '@/api/types'

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
      setError(err instanceof Error ? err.message : '加载订单失败')
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
      setError(err instanceof Error ? err.message : '支付失败')
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
      setError(err instanceof Error ? err.message : '取消失败')
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
      setError(err instanceof Error ? err.message : '退款失败')
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
      setError(err instanceof Error ? err.message : '确认收货失败')
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
      setError(err instanceof Error ? err.message : '申请退款失败')
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
          title="我的订单"
          icon={<ClipboardList className="w-5 h-5 text-brand-600" />}
          count={total}
        />
        <Button variant="ghost" size="sm" onClick={load} icon={<RefreshCw className="w-4 h-4" />}>
          刷新
        </Button>
      </div>

      <Tabs
        activeKey={unreviewedMode ? 'unreviewed' : (activeStatus ?? 'all')}
        onTabChange={(key) => {
          if (key === 'unreviewed') {
            setUnreviewedMode(true)
            setActiveStatus(undefined)
          } else {
            setUnreviewedMode(false)
            setActiveStatus(key === 'all' ? undefined : (key as OrderStatus))
          }
        }}
        tabs={[
          { key: 'all', label: '全部', content: null },
          { key: 'PENDING', label: '未付款', content: null },
          { key: 'PAID', label: '未发货', content: null },
          { key: 'SHIPPED', label: '未签收', content: null },
          { key: 'COMPLETED', label: '已完成', content: null },
          { key: 'unreviewed', label: '未评价', content: null },
        ]}
      />

      {loading && <p className="text-sm text-slate-500">加载中…</p>}
      {error && <p className="text-sm text-red-600">{error}</p>}

      {!loading && !error && (
        <Table<Order>
          columns={[
            {
              key: 'order',
              header: '订单',
              render: (o: Order) => (
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
              ),
            },
            {
              key: 'customerName',
              header: '客户',
              render: (o: Order) => <span className="text-slate-800">{o.customerName}</span>,
            },
            {
              key: 'total',
              header: '总额',
              className: 'text-right',
              render: (o: Order) => <span className="text-slate-700">{formatPrice(o.totalInCents)}</span>,
            },
            {
              key: 'status',
              header: '状态',
              render: (o: Order) =>
                o.status === 'REFUND_PENDING' ? (
                  <StatusBadge status={o.status} label="退款处理中" />
                ) : (
                  <StatusBadge status={o.status} />
                ),
            },
            {
              key: 'createTime',
              header: '创建时间',
              render: (o: Order) => (
                <span className="text-slate-500">{o.createTime ? new Date(o.createTime).toLocaleString() : '—'}</span>
              ),
            },
            {
              key: 'actions',
              header: '操作',
              className: 'text-right',
              render: (o: Order) => (
                <div className="space-x-2">
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
                    o.status !== 'REFUND_PENDING' && <span className="text-xs text-slate-300">—</span>}
                </div>
              ),
            },
          ]}
          data={rows}
          rowKey={(o) => String(o.id)}
          empty={
            <>
              没有订单。{' '}
              <Button variant="ghost" size="sm" onClick={() => navigate('/products')}>
                去购物
              </Button>
            </>
          }
          expandableRow={(o: Order) =>
            expandedId === o.id ? (
              itemsLoading ? (
                <p className="text-sm text-slate-500">加载商品…</p>
              ) : items.length === 0 ? (
                <p className="text-sm text-slate-400">暂无商品</p>
              ) : (
                <div className="space-y-2">
                  {o.trackingNumber &&
                    (o.status === 'SHIPPED' ||
                      o.status === 'DELIVERED' ||
                      o.status === 'COMPLETED') && (
                      <p className="text-sm text-slate-600">
                        运单号：<span className="font-mono">{o.trackingNumber}</span>
                      </p>
                    )}
                  <ul className="divide-y divide-slate-200">
                    {items.map((it) => (
                      <li key={it.id} className="flex items-center justify-between py-1.5">
                        <span className="text-slate-700">{it.name}</span>
                        <span className="text-slate-500">×{it.quantity}</span>
                        <span className="text-slate-700">{formatPrice(it.priceInCents)}</span>
                        {(o.status === 'DELIVERED' || o.status === 'COMPLETED') && (
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
                            {(reviewsByOrder[o.id] ?? []).some((r) => r.productId === it.productId)
                              ? '编辑评价'
                              : '写评价'}
                          </button>
                        )}
                      </li>
                    ))}
                  </ul>
                </div>
              )
            ) : null
          }
          expandedRowKey={expandedId !== null ? String(expandedId) : null}
        />
      )}

      <Modal
        open={refundOrderId != null}
        onClose={closeRefundModal}
        title="申请退款"
        footer={
          <>
            <Button variant="ghost" size="sm" onClick={closeRefundModal}>
              取消
            </Button>
            <Button onClick={submitRefund} disabled={busyId === refundOrderId}>
              {busyId === refundOrderId ? '提交中…' : '提交'}
            </Button>
          </>
        }
      >
        <p className="text-sm text-slate-500">订单 #{refundOrderId}</p>
        <Textarea
          value={refundReason}
          onChange={(e) => setRefundReason(e.target.value)}
          placeholder="请输入退款原因"
          rows={3}
        />
      </Modal>

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
                .then((r) => setReviewsByOrder((p) => ({ ...p, [expandedId]: r })))
                .catch(() => {})
            }
            await load()
          }}
        />
      )}
    </div>
  )
}
