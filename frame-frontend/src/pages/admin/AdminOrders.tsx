import { useEffect, useState } from 'react'
import { Shield, Package, CheckCheck, RefreshCw } from 'lucide-react'
import {
  adminListOrders,
  adminShip,
  adminDeliver,
  adminRefundRequests,
  adminApproveRefund,
  adminDenyRefund,
} from '@/api/order'
import type { Order, OrderStatus } from '@/api/types'
import { formatPrice } from '@/lib/format'
import { useIsAdmin } from '@/store/useAuthStore'
import Button from '@/components/ui/Button'
import Input from '@/components/ui/Input'
import Select from '@/components/ui/Select'
import StatusBadge from '@/components/ui/StatusBadge'
import Table from '@/components/ui/Table'

/** Status-filter options for the admin orders table. Labels match StatusBadge (Task 3). */
const ORDER_STATUS_FILTERS: { label: string; value?: OrderStatus }[] = [
  { label: '全部' },
  { label: '待付款', value: 'PENDING' },
  { label: '已付款', value: 'PAID' },
  { label: '已发货', value: 'SHIPPED' },
  { label: '已签收', value: 'DELIVERED' },
  { label: '已完成', value: 'COMPLETED' },
  { label: '退款处理中', value: 'REFUND_PENDING' },
  { label: '已退款', value: 'REFUNDED' },
  { label: '已取消', value: 'CANCELLED' },
  { label: '失败', value: 'FAILED' },
]

/**
 * Admin orders page: shipping + refunds. Loads automatically for admins via
 * RequireAdmin; the local useIsAdmin gate is defense-in-depth for direct renders.
 */
export default function AdminOrders() {
  const isAdmin = useIsAdmin()
  const [orders, setOrders] = useState<Order[]>([])
  const [ordersTotal, setOrdersTotal] = useState(0)
  const [ordersLoading, setOrdersLoading] = useState(false)
  const [ordersError, setOrdersError] = useState<string | null>(null)
  const [statusFilter, setStatusFilter] = useState<OrderStatus | undefined>(undefined)
  const [busyOrderId, setBusyOrderId] = useState<number | null>(null)
  // Per-row tracking-number input (keyed by order id) for the Ship action.
  const [trackingInputs, setTrackingInputs] = useState<Record<number, string>>({})

  // --- Refunds sub-section state ---
  const [refunds, setRefunds] = useState<Order[]>([])
  const [refundsLoading, setRefundsLoading] = useState(false)
  const [refundsError, setRefundsError] = useState<string | null>(null)

  async function loadOrders() {
    setOrdersLoading(true)
    setOrdersError(null)
    try {
      const res = await adminListOrders(statusFilter)
      setOrders(res.records)
      setOrdersTotal(res.total)
    } catch (err) {
      setOrdersError(err instanceof Error ? err.message : '加载订单失败')
    } finally {
      setOrdersLoading(false)
    }
  }

  // (Re)load orders whenever the status filter changes.
  useEffect(() => {
    if (isAdmin) void loadOrders()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAdmin, statusFilter])

  async function loadRefunds() {
    setRefundsLoading(true)
    setRefundsError(null)
    try {
      const res = await adminRefundRequests()
      setRefunds(res.records)
    } catch (err) {
      setRefundsError(err instanceof Error ? err.message : '加载退款失败')
    } finally {
      setRefundsLoading(false)
    }
  }

  // Load refunds on mount.
  useEffect(() => {
    if (isAdmin) void loadRefunds()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAdmin])

  async function handleShip(id: number) {
    const tracking = (trackingInputs[id] ?? '').trim()
    if (!tracking) {
      setOrdersError('请先输入运单号再发货')
      return
    }
    setBusyOrderId(id)
    setOrdersError(null)
    try {
      await adminShip(id, tracking)
      setTrackingInputs((prev) => ({ ...prev, [id]: '' }))
      await Promise.all([loadOrders(), loadRefunds()])
    } catch (err) {
      setOrdersError(err instanceof Error ? err.message : '发货失败')
    } finally {
      setBusyOrderId(null)
    }
  }

  async function handleDeliver(id: number) {
    setBusyOrderId(id)
    setOrdersError(null)
    try {
      await adminDeliver(id)
      await loadOrders()
    } catch (err) {
      setOrdersError(err instanceof Error ? err.message : '送达失败')
    } finally {
      setBusyOrderId(null)
    }
  }

  async function handleApproveRefund(orderId: number) {
    setBusyOrderId(orderId)
    setRefundsError(null)
    try {
      await adminApproveRefund(orderId)
      await Promise.all([loadRefunds(), loadOrders()])
    } catch (err) {
      setRefundsError(err instanceof Error ? err.message : '批准退款失败')
    } finally {
      setBusyOrderId(null)
    }
  }

  async function handleDenyRefund(orderId: number) {
    setBusyOrderId(orderId)
    setRefundsError(null)
    try {
      await adminDenyRefund(orderId)
      await Promise.all([loadRefunds(), loadOrders()])
    } catch (err) {
      setRefundsError(err instanceof Error ? err.message : '拒绝退款失败')
    } finally {
      setBusyOrderId(null)
    }
  }

  if (!isAdmin) {
    return (
      <div className="flex flex-col items-center gap-3 py-16 text-center">
        <Shield className="w-10 h-10 text-slate-300" />
        <h1 className="text-lg font-semibold text-slate-700">需要管理员权限</h1>
        <p className="text-sm text-slate-500">
          您没有权限查看此页面。
        </p>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-2">
        <Package className="w-5 h-5 text-brand-600" />
        <h1 className="text-xl font-semibold text-slate-800">订单管理</h1>
        <span className="text-sm text-slate-400">({ordersTotal} 笔订单)</span>
      </div>

      {/* Refunds sub-section */}
      <section className="space-y-3">
        <div className="flex items-center gap-2">
          <RefreshCw className="w-4 h-4 text-orange-600" />
          <h2 className="text-sm font-medium text-slate-700">退款申请</h2>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => { void loadRefunds(); void loadOrders() }}
            className="ml-auto"
          >
            刷新
          </Button>
        </div>
        {refundsError && <p className="text-sm text-red-600">{refundsError}</p>}
        {refundsLoading ? (
          <p className="text-sm text-slate-500">加载退款中…</p>
        ) : refunds.length === 0 ? (
          <p className="text-sm text-slate-400">暂无待处理的退款申请。</p>
        ) : (
          <Table<Order>
            rowKey={(o) => String(o.id)}
            data={refunds}
            empty="暂无待处理的退款申请。"
            columns={[
              { key: 'id', header: '订单', render: (o) => <span className="text-slate-500">#{o.id}</span> },
              { key: 'customerName', header: '客户', render: (o) => <span className="text-slate-800">{o.customerName}</span> },
              {
                key: 'refundReason',
                header: '原因',
                render: (o) => (
                  <span className="text-slate-600 max-w-xs truncate">{o.refundReason || '—'}</span>
                ),
              },
              {
                key: 'actions',
                header: '操作',
                className: 'text-right',
                render: (o) => (
                  <div className="flex items-center justify-end gap-2">
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => handleApproveRefund(o.id)}
                      disabled={busyOrderId === o.id}
                      className="text-green-600"
                    >
                      {busyOrderId === o.id ? '…' : '批准'}
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => handleDenyRefund(o.id)}
                      disabled={busyOrderId === o.id}
                      className="text-red-600"
                    >
                      {busyOrderId === o.id ? '…' : '拒绝'}
                    </Button>
                  </div>
                ),
              },
            ]}
          />
        )}
      </section>

      {/* Orders table */}
      <section className="space-y-3">
        <div className="flex items-center gap-2">
          <Package className="w-4 h-4 text-brand-600" />
          <h2 className="text-sm font-medium text-slate-700">所有订单</h2>
          <label className="ml-auto flex items-center gap-1.5 text-xs text-slate-500">
            状态
            <Select
              value={statusFilter ?? ''}
              onChange={(e) =>
                setStatusFilter((e.target.value || undefined) as OrderStatus | undefined)
              }
              className="w-auto py-1"
            >
              {ORDER_STATUS_FILTERS.map((f) => (
                <option key={f.label} value={f.value ?? ''}>
                  {f.label}
                </option>
              ))}
            </Select>
          </label>
        </div>
        {ordersError && <p className="text-sm text-red-600">{ordersError}</p>}
        {ordersLoading ? (
          <p className="text-sm text-slate-500">加载中…</p>
        ) : (
          <Table<Order>
            rowKey={(o) => String(o.id)}
            data={orders}
            empty={`暂无订单${statusFilter ? `（状态：${statusFilter}）` : ''}。`}
            columns={[
              { key: 'id', header: '订单', render: (o) => <span className="text-slate-500">#{o.id}</span> },
              {
                key: 'customerName',
                header: '客户',
                render: (o) => (
                  <span className="text-slate-800">
                    {o.customerName}
                    {o.customerPhone && (
                      <span className="block text-xs text-slate-400">{o.customerPhone}</span>
                    )}
                  </span>
                ),
              },
              {
                key: 'totalInCents',
                header: '总额',
                className: 'text-right',
                render: (o) => <span className="text-slate-700">{formatPrice(o.totalInCents)}</span>,
              },
              { key: 'status', header: '状态', render: (o) => <StatusBadge status={o.status} /> },
              {
                key: 'trackingNumber',
                header: '物流单号',
                render: (o) => (
                  <span className="text-slate-600 font-mono text-xs">{o.trackingNumber || '—'}</span>
                ),
              },
              {
                key: 'actions',
                header: '操作',
                className: 'text-right',
                render: (o) => (
                  <>
                    {o.status === 'PAID' && (
                      <div className="flex items-center justify-end gap-1.5">
                        <Input
                          placeholder="物流单号"
                          value={trackingInputs[o.id] ?? ''}
                          onChange={(e) =>
                            setTrackingInputs((prev) => ({ ...prev, [o.id]: e.target.value }))
                          }
                          className="w-28 text-xs py-1"
                        />
                        <Button
                          variant="primary"
                          size="sm"
                          onClick={() => handleShip(o.id)}
                          disabled={busyOrderId === o.id}
                          loading={busyOrderId === o.id}
                          icon={<Package className="w-3 h-3" />}
                        >
                          发货
                        </Button>
                      </div>
                    )}
                    {o.status === 'SHIPPED' && (
                      <Button
                        variant="primary"
                        size="sm"
                        onClick={() => handleDeliver(o.id)}
                        disabled={busyOrderId === o.id}
                        loading={busyOrderId === o.id}
                        icon={<CheckCheck className="w-3 h-3" />}
                      >
                        送达
                      </Button>
                    )}
                    {o.status !== 'PAID' && o.status !== 'SHIPPED' && (
                      <span className="text-xs text-slate-300">—</span>
                    )}
                  </>
                ),
              },
            ]}
          />
        )}
      </section>
    </div>
  )
}
