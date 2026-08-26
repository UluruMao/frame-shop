import { useEffect, useState, type FormEvent } from 'react'
import { Shield, Zap, Pencil, Trash2, Plus, Package, CheckCheck, RefreshCw, MessageCircle } from 'lucide-react'
import {
  pageProducts,
  createProduct,
  updateProduct,
  deleteProduct,
  openFlashSale,
} from '@/api/product'
import {
  adminListOrders,
  adminShip,
  adminDeliver,
  adminRefundRequests,
  adminApproveRefund,
  adminDenyRefund,
} from '@/api/order'
import { adminInbox, adminHistory, type ChatSessionVO, type ChatMessageVO } from '@/api/chat'
import { useChat } from '@/hooks/useChat'
import type { Order, OrderStatus, Product } from '@/api/types'
import { formatPrice } from '@/lib/format'
import { useIsAdmin } from '@/store/useAuthStore'
import Button from '@/components/ui/Button'
import Input, { Textarea } from '@/components/ui/Input'
import Select from '@/components/ui/Select'
import StatusBadge from '@/components/ui/StatusBadge'
import Badge from '@/components/ui/Badge'
import Tabs from '@/components/ui/Tabs'
import Table from '@/components/ui/Table'

/** Top-level Admin tabs: Products (CRUD), Orders (shipping/refund), and Chat (inbox). */
type AdminTab = 'products' | 'orders' | 'chat'

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
 * Convert an ISO datetime string (e.g. "2026-08-17T13:00:00") to the
 * `YYYY-MM-DDTHH:MM` format a `datetime-local` input requires (no seconds/ms).
 * Returns '' for null/empty so the input stays empty for new products.
 */
function toDatetimeLocal(iso: string | undefined): string {
  if (!iso) return ''
  // Strip seconds/milliseconds/timezone: "2026-08-17T13:00:00" → "2026-08-17T13:00"
  const spaceIdx = iso.indexOf('T')
  if (spaceIdx === -1) return iso.slice(0, 16)
  return iso.slice(0, 16)
}

interface FormState {
  name: string
  priceInCents: string
  stock: string
  category: string
  imageUrl: string
  description: string
  isFlashSale: boolean
  flashSaleAt: string
}

const EMPTY_FORM: FormState = {
  name: '',
  priceInCents: '',
  stock: '',
  category: '',
  imageUrl: '',
  description: '',
  isFlashSale: false,
  flashSaleAt: '',
}

/**
 * Admin page: product CRUD + "open flash sale" (loads stock into Redis).
 * The backend gates /products mutations and /admin/sales to ADMIN role;
 * this page is only routed to admins. Non-admins hitting it get a notice.
 */
export default function Admin() {
  // Client-side role gate: the nav link is hidden for non-admins, but the
  // route itself isn't guarded, so block direct navigation to /admin.
  const isAdmin = useIsAdmin()
  const [tab, setTab] = useState<AdminTab>('products')
  const [rows, setRows] = useState<Product[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [form, setForm] = useState<FormState>(EMPTY_FORM)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [saving, setSaving] = useState(false)
  const [saleId, setSaleId] = useState<number | null>(null)

  // --- Orders tab state ---
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

  // --- Chat inbox tab state ---
  const [inbox, setInbox] = useState<ChatSessionVO[]>([])
  const [inboxLoading, setInboxLoading] = useState(false)
  const [inboxError, setInboxError] = useState<string | null>(null)
  const [selectedSessionId, setSelectedSessionId] = useState<number | null>(null)

  async function load() {
    setLoading(true)
    setError(null)
    try {
      const res = await pageProducts({ current: 1, size: 100 })
      setRows(res.records)
      setTotal(res.total)
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载商品失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    // Skip the fetch for non-admins — they'll hit the access-denied notice below.
    if (!isAdmin) return
    void load()
  }, [isAdmin])

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

  function resetForm() {
    setForm(EMPTY_FORM)
    setEditingId(null)
  }

  function startEdit(p: Product) {
    setEditingId(p.id)
    setForm({
      name: p.name,
      priceInCents: String(p.priceInCents),
      stock: String(p.stock ?? 0),
      category: p.category ?? '',
      imageUrl: p.imageUrl ?? '',
      description: p.description ?? '',
      isFlashSale: !!p.isFlashSale,
      flashSaleAt: toDatetimeLocal(p.flashSaleAt),
    })
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setSaving(true)
    setError(null)
    const payload = {
      name: form.name,
      priceInCents: Number(form.priceInCents),
      stock: Number(form.stock),
      category: form.category || undefined,
      imageUrl: form.imageUrl || undefined,
      description: form.description || undefined,
      isFlashSale: form.isFlashSale,
      flashSaleAt: form.isFlashSale && form.flashSaleAt ? form.flashSaleAt : undefined,
    }
    try {
      if (editingId !== null) {
        await updateProduct(editingId, payload)
      } else {
        await createProduct(payload)
      }
      resetForm()
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  async function handleDelete(id: number) {
    if (!confirm(`确认删除商品 #${id}？`)) return
    try {
      await deleteProduct(id)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除失败')
    }
  }

  async function handleOpenSale(id: number) {
    setSaleId(id)
    setError(null)
    try {
      await openFlashSale(id)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : '开启秒杀失败')
    } finally {
      setSaleId(null)
    }
  }

  // --- Orders tab: load + admin actions (ship/deliver) ---

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

  // (Re)load orders whenever the status filter changes while on the orders tab.
  useEffect(() => {
    if (isAdmin && tab === 'orders') void loadOrders()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAdmin, tab, statusFilter])

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

  // --- Refunds sub-section ---

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

  // Load refunds whenever the orders tab is shown.
  useEffect(() => {
    if (isAdmin && tab === 'orders') void loadRefunds()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAdmin, tab])

  // --- Chat inbox tab ---

  async function loadInbox() {
    setInboxLoading(true)
    setInboxError(null)
    try {
      const sessions = await adminInbox()
      setInbox(sessions)
    } catch (err) {
      setInboxError(err instanceof Error ? err.message : '加载会话失败')
    } finally {
      setInboxLoading(false)
    }
  }

  // Load the open sessions whenever the chat tab is shown.
  useEffect(() => {
    if (isAdmin && tab === 'chat') void loadInbox()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAdmin, tab])

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

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <Shield className="w-5 h-5 text-brand-600" />
        <h1 className="text-xl font-semibold text-slate-800">管理后台</h1>
        {tab === 'products' && (
          <span className="text-sm text-slate-400">({total} 件商品)</span>
        )}
        {tab === 'orders' && (
          <span className="text-sm text-slate-400">({ordersTotal} 笔订单)</span>
        )}
        {tab === 'chat' && (
          <span className="text-sm text-slate-400">({inbox.length} 个开放会话)</span>
        )}
      </div>

      {/* Tab switcher */}
      <Tabs<AdminTab>
        activeKey={tab}
        onTabChange={setTab}
        tabs={[
          {
            key: 'products',
            label: (<span className="flex items-center gap-1.5"><Pencil className="w-3.5 h-3.5" />商品</span>),
            content: <></>,
          },
          {
            key: 'orders',
            label: (<span className="flex items-center gap-1.5"><Package className="w-3.5 h-3.5" />订单</span>),
            content: <></>,
          },
          {
            key: 'chat',
            label: (<span className="flex items-center gap-1.5"><MessageCircle className="w-3.5 h-3.5" />客服</span>),
            content: <></>,
          },
        ]}
      />

      {tab === 'products' && (
        <>
          {error && <p className="text-sm text-red-600">{error}</p>}

          {/* Create / edit form */}
          <form onSubmit={handleSubmit} className="bg-white border border-slate-200 rounded-lg p-4 space-y-3">
        <h2 className="text-sm font-medium text-slate-700">
          {editingId !== null ? `编辑商品 #${editingId}` : '新建商品'}
        </h2>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <Input
            placeholder="名称"
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            required
          />
          <Input
            placeholder="价格 (分)"
            type="number"
            min={0}
            value={form.priceInCents}
            onChange={(e) => setForm({ ...form, priceInCents: e.target.value })}
            required
          />
          <Input
            placeholder="库存"
            type="number"
            min={0}
            value={form.stock}
            onChange={(e) => setForm({ ...form, stock: e.target.value })}
            required
          />
          <Input
            placeholder="分类 (可选)"
            value={form.category}
            onChange={(e) => setForm({ ...form, category: e.target.value })}
          />
          <Input
            placeholder="图片链接 (可选)"
            value={form.imageUrl}
            onChange={(e) => setForm({ ...form, imageUrl: e.target.value })}
          />
        </div>
        <Textarea
          placeholder="描述 (可选)"
          value={form.description}
          onChange={(e) => setForm({ ...form, description: e.target.value })}
          rows={2}
        />
        <label className="flex items-center gap-2 text-sm text-slate-600">
          <input
            type="checkbox"
            checked={form.isFlashSale}
            onChange={(e) => setForm({ ...form, isFlashSale: e.target.checked })}
          />
          秒杀商品
        </label>
        {form.isFlashSale && (
          <label className="flex flex-col gap-1 text-sm text-slate-600">
            <span className="text-xs text-slate-500">
              秒杀开始时间（通过定时任务自动开始；留空则手动开启）
            </span>
            <Input
              type="datetime-local"
              value={form.flashSaleAt}
              onChange={(e) => setForm({ ...form, flashSaleAt: e.target.value })}
            />
          </label>
        )}
        <div className="flex gap-2">
          <Button
            type="submit"
            loading={saving}
            icon={<Plus className="w-4 h-4" />}
          >
            {editingId !== null ? '更新' : '创建'}
          </Button>
          {editingId !== null && (
            <Button
              type="button"
              variant="secondary"
              onClick={resetForm}
            >
              取消编辑
            </Button>
          )}
        </div>
      </form>

      {/* Product table */}
      {loading && <p className="text-sm text-slate-500">加载中…</p>}
      {!loading && (
        <Table<Product>
          rowKey={(p) => String(p.id)}
          data={rows}
          empty="暂无商品，请在上方创建。"
          columns={[
            { key: 'id', header: 'ID', render: (p) => <span className="text-slate-500">{p.id}</span> },
            { key: 'name', header: '名称', render: (p) => <span className="text-slate-800">{p.name}</span> },
            { key: 'priceInCents', header: '价格', className: 'text-right', render: (p) => <span className="text-slate-700">{formatPrice(p.priceInCents)}</span> },
            { key: 'stock', header: '库存', className: 'text-right', render: (p) => <span className="text-slate-700">{p.stock}</span> },
            {
              key: 'isFlashSale',
              header: '秒杀',
              render: (p) =>
                p.isFlashSale ? <Badge color="amber">是</Badge> : <Badge color="slate">否</Badge>,
            },
            {
              key: 'flashSaleAt',
              header: '开始时间',
              render: (p) =>
                p.flashSaleAt ? (
                  <span className="text-xs text-slate-600">
                    {new Date(p.flashSaleAt).toLocaleString(undefined, {
                      month: 'short',
                      day: 'numeric',
                      hour: '2-digit',
                      minute: '2-digit',
                    })}
                  </span>
                ) : (
                  <span className="text-slate-300">—</span>
                ),
            },
            {
              key: 'actions',
              header: '操作',
              className: 'text-right',
              render: (p) => (
                <div className="flex items-center justify-end gap-1">
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => handleOpenSale(p.id)}
                    disabled={saleId === p.id}
                    loading={saleId === p.id}
                    title="开启秒杀（加载库存到 Redis）"
                    icon={<Zap className="w-4 h-4" />}
                  />
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => startEdit(p)}
                    title="编辑"
                    icon={<Pencil className="w-4 h-4" />}
                  />
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => handleDelete(p.id)}
                    title="删除"
                    icon={<Trash2 className="w-4 h-4" />}
                    className="text-red-600 hover:bg-red-50"
                  />
                </div>
              ),
            },
          ]}
        />
      )}
        </>
      )}

      {/* --- Orders tab --- */}
      {tab === 'orders' && (
        <div className="space-y-6">
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
      )}

      {/* --- Chat inbox tab --- */}
      {tab === 'chat' && (
        <div className="space-y-4">
          <section className="space-y-3">
            <div className="flex items-center gap-2">
              <MessageCircle className="w-4 h-4 text-brand-600" />
              <h2 className="text-sm font-medium text-slate-700">开放会话</h2>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => { void loadInbox() }}
                className="ml-auto"
              >
                刷新
              </Button>
            </div>
            {inboxError && <p className="text-sm text-red-600">{inboxError}</p>}
            {inboxLoading ? (
              <p className="text-sm text-slate-500">加载中…</p>
            ) : inbox.length === 0 ? (
              <p className="text-sm text-slate-400">暂无开放会话。</p>
            ) : (
              <Table<ChatSessionVO>
                rowKey={(s) => String(s.id)}
                data={inbox}
                empty="暂无开放会话。"
                columns={[
                  { key: 'id', header: '会话', render: (s) => <span className="text-slate-500">#{s.id}</span> },
                  { key: 'userId', header: '用户', render: (s) => <span className="text-slate-800">用户 #{s.userId}</span> },
                  { key: 'status', header: '状态', render: (s) => <Badge color="green">{s.status}</Badge> },
                  {
                    key: 'createTime',
                    header: '创建时间',
                    render: (s) => (
                      <span className="text-xs text-slate-600">
                        {s.createTime
                          ? new Date(s.createTime).toLocaleString(undefined, {
                              month: 'short',
                              day: 'numeric',
                              hour: '2-digit',
                              minute: '2-digit',
                            })
                          : '—'}
                      </span>
                    ),
                  },
                  {
                    key: 'actions',
                    header: '操作',
                    className: 'text-right',
                    render: (s) => (
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => setSelectedSessionId(s.id)}
                      >
                        {selectedSessionId === s.id ? '已选' : '打开'}
                      </Button>
                    ),
                  },
                ]}
              />
            )}
          </section>

          {/* Reply panel for the selected session. Rendered as a separate
              component so useChat(sessionId) is called unconditionally inside
              it (Rules of Hooks) — the parent gates rendering, not the hook. */}
          {selectedSessionId !== null && (
            <ChatReplyPanel sessionId={selectedSessionId} />
          )}
        </div>
      )}
    </div>
  )
}

/**
 * Admin reply panel for a single chat session. Loads history via adminHistory,
 * then useChat(sessionId) subscribes to the live topic so the admin sees new
 * messages. Reply input sends to /app/chat/{sessionId} (same destination as the
 * user; the backend tags admin messages with senderType=ADMIN).
 */
function ChatReplyPanel({ sessionId }: { sessionId: number }) {
  const { messages, send, connected, setMessages } = useChat(sessionId)
  const [input, setInput] = useState('')
  const [historyLoading, setHistoryLoading] = useState(true)

  useEffect(() => {
    setHistoryLoading(true)
    adminHistory(sessionId)
      .then((r) => setMessages(r.records))
      .catch(() => {})
      .finally(() => setHistoryLoading(false))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId])

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (!input.trim()) return
    send(input.trim(), null)
    setInput('')
  }

  return (
    <section className="space-y-3">
      <div className="flex items-center gap-2">
        <h2 className="text-sm font-medium text-slate-700">会话 #{sessionId}</h2>
        <span className="text-xs text-slate-400">{connected ? '已连接' : '连接中…'}</span>
      </div>
      <div className="h-80 overflow-y-auto rounded border border-slate-200 p-3 bg-slate-50">
        {historyLoading && <p className="text-sm text-slate-500">加载历史记录…</p>}
        {messages.map((m: ChatMessageVO) => (
          <div key={m.id} className={`mb-2 ${m.senderType === 'ADMIN' ? 'text-right' : 'text-left'}`}>
            <span
              className={`inline-block rounded px-3 py-1 ${
                m.senderType === 'ADMIN' ? 'bg-brand-600 text-white' : 'bg-slate-200 text-slate-800'
              }`}
            >
              {m.content}
            </span>
          </div>
        ))}
        {!historyLoading && messages.length === 0 && (
          <p className="text-sm text-slate-400 text-center">暂无消息。</p>
        )}
      </div>
      <form onSubmit={submit} className="flex gap-2">
        <Input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="回复消息…"
        />
        <Button
          type="submit"
          disabled={!connected}
        >
          发送
        </Button>
      </form>
    </section>
  )
}
