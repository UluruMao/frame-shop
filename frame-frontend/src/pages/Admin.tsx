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

/** Top-level Admin tabs: Products (CRUD), Orders (shipping/refund), and Chat (inbox). */
type AdminTab = 'products' | 'orders' | 'chat'

/** Status-filter options for the admin orders table. */
const ORDER_STATUS_FILTERS: { label: string; value?: OrderStatus }[] = [
  { label: 'All' },
  { label: 'Pending', value: 'PENDING' },
  { label: 'Paid', value: 'PAID' },
  { label: 'Shipped', value: 'SHIPPED' },
  { label: 'Delivered', value: 'DELIVERED' },
  { label: 'Completed', value: 'COMPLETED' },
  { label: 'Refund pending', value: 'REFUND_PENDING' },
  { label: 'Refunded', value: 'REFUNDED' },
  { label: 'Cancelled', value: 'CANCELLED' },
  { label: 'Failed', value: 'FAILED' },
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
  description: string
  isFlashSale: boolean
  flashSaleAt: string
}

const EMPTY_FORM: FormState = {
  name: '',
  priceInCents: '',
  stock: '',
  category: '',
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
      setError(err instanceof Error ? err.message : 'failed to load products')
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
        <h1 className="text-lg font-semibold text-slate-700">Admin access required</h1>
        <p className="text-sm text-slate-500">
          You don't have permission to view this page.
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
      setError(err instanceof Error ? err.message : 'save failed')
    } finally {
      setSaving(false)
    }
  }

  async function handleDelete(id: number) {
    if (!confirm(`Delete product #${id}?`)) return
    try {
      await deleteProduct(id)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'delete failed')
    }
  }

  async function handleOpenSale(id: number) {
    setSaleId(id)
    setError(null)
    try {
      await openFlashSale(id)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'failed to open sale')
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
      setOrdersError(err instanceof Error ? err.message : 'failed to load orders')
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
      setOrdersError('Enter a tracking number before shipping')
      return
    }
    setBusyOrderId(id)
    setOrdersError(null)
    try {
      await adminShip(id, tracking)
      setTrackingInputs((prev) => ({ ...prev, [id]: '' }))
      await Promise.all([loadOrders(), loadRefunds()])
    } catch (err) {
      setOrdersError(err instanceof Error ? err.message : 'ship failed')
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
      setOrdersError(err instanceof Error ? err.message : 'deliver failed')
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
      setRefundsError(err instanceof Error ? err.message : 'failed to load refunds')
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
      setInboxError(err instanceof Error ? err.message : 'failed to load inbox')
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
      setRefundsError(err instanceof Error ? err.message : 'approve refund failed')
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
      setRefundsError(err instanceof Error ? err.message : 'deny refund failed')
    } finally {
      setBusyOrderId(null)
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <Shield className="w-5 h-5 text-brand-600" />
        <h1 className="text-xl font-semibold text-slate-800">Admin</h1>
        {tab === 'products' && (
          <span className="text-sm text-slate-400">({total} products)</span>
        )}
        {tab === 'orders' && (
          <span className="text-sm text-slate-400">({ordersTotal} orders)</span>
        )}
        {tab === 'chat' && (
          <span className="text-sm text-slate-400">({inbox.length} open sessions)</span>
        )}
      </div>

      {/* Tab switcher */}
      <div className="flex items-center gap-1 border-b border-slate-200">
        <button
          onClick={() => setTab('products')}
          className={[
            'px-3 py-2 text-sm border-b-2 -mb-px transition-colors flex items-center gap-1.5',
            tab === 'products'
              ? 'border-brand-600 text-brand-600 font-medium'
              : 'border-transparent text-slate-600 hover:text-slate-800',
          ].join(' ')}
        >
          <Pencil className="w-3.5 h-3.5" />
          Products
        </button>
        <button
          onClick={() => setTab('orders')}
          className={[
            'px-3 py-2 text-sm border-b-2 -mb-px transition-colors flex items-center gap-1.5',
            tab === 'orders'
              ? 'border-brand-600 text-brand-600 font-medium'
              : 'border-transparent text-slate-600 hover:text-slate-800',
          ].join(' ')}
        >
          <Package className="w-3.5 h-3.5" />
          Orders
        </button>
        <button
          onClick={() => setTab('chat')}
          className={[
            'px-3 py-2 text-sm border-b-2 -mb-px transition-colors flex items-center gap-1.5',
            tab === 'chat'
              ? 'border-brand-600 text-brand-600 font-medium'
              : 'border-transparent text-slate-600 hover:text-slate-800',
          ].join(' ')}
        >
          <MessageCircle className="w-3.5 h-3.5" />
          Chat
        </button>
      </div>

      {tab === 'products' && (
        <>
          {error && <p className="text-sm text-red-600">{error}</p>}

          {/* Create / edit form */}
          <form onSubmit={handleSubmit} className="bg-white border border-slate-200 rounded-lg p-4 space-y-3">
        <h2 className="text-sm font-medium text-slate-700">
          {editingId !== null ? `Edit product #${editingId}` : 'New product'}
        </h2>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <Input
            placeholder="Name"
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            required
          />
          <Input
            placeholder="Price (cents)"
            type="number"
            min={0}
            value={form.priceInCents}
            onChange={(e) => setForm({ ...form, priceInCents: e.target.value })}
            required
          />
          <Input
            placeholder="Stock"
            type="number"
            min={0}
            value={form.stock}
            onChange={(e) => setForm({ ...form, stock: e.target.value })}
            required
          />
          <Input
            placeholder="Category (optional)"
            value={form.category}
            onChange={(e) => setForm({ ...form, category: e.target.value })}
          />
        </div>
        <Textarea
          placeholder="Description (optional)"
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
          Flash sale item
        </label>
        {form.isFlashSale && (
          <label className="flex flex-col gap-1 text-sm text-slate-600">
            <span className="text-xs text-slate-500">
              Flash sale opens at (auto-opens via scheduled job; leave blank for manual open)
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
            {editingId !== null ? 'Update' : 'Create'}
          </Button>
          {editingId !== null && (
            <Button
              type="button"
              variant="secondary"
              onClick={resetForm}
            >
              Cancel edit
            </Button>
          )}
        </div>
      </form>

      {/* Product table */}
      {loading && <p className="text-sm text-slate-500">Loading…</p>}
      {!loading && (
        <div className="bg-white border border-slate-200 rounded-lg overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-slate-50 text-slate-600">
              <tr>
                <th className="text-left px-4 py-2 font-medium">ID</th>
                <th className="text-left px-4 py-2 font-medium">Name</th>
                <th className="text-right px-4 py-2 font-medium">Price</th>
                <th className="text-right px-4 py-2 font-medium">Stock</th>
                <th className="text-left px-4 py-2 font-medium">Flash</th>
                <th className="text-left px-4 py-2 font-medium">Sale at</th>
                <th className="text-right px-4 py-2 font-medium">Actions</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((p) => (
                <tr key={p.id} className="border-t border-slate-100">
                  <td className="px-4 py-2 text-slate-500">{p.id}</td>
                  <td className="px-4 py-2 text-slate-800">{p.name}</td>
                  <td className="px-4 py-2 text-right text-slate-700">{formatPrice(p.priceInCents)}</td>
                  <td className="px-4 py-2 text-right text-slate-700">{p.stock}</td>
                  <td className="px-4 py-2">
                    {p.isFlashSale ? (
                      <Badge color="amber">Yes</Badge>
                    ) : (
                      <Badge color="slate">No</Badge>
                    )}
                  </td>
                  <td className="px-4 py-2 text-xs text-slate-600">
                    {p.flashSaleAt
                      ? new Date(p.flashSaleAt).toLocaleString(undefined, {
                          month: 'short',
                          day: 'numeric',
                          hour: '2-digit',
                          minute: '2-digit',
                        })
                      : <span className="text-slate-300">—</span>}
                  </td>
                  <td className="px-4 py-2 text-right">
                    <div className="flex items-center justify-end gap-1">
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => handleOpenSale(p.id)}
                        disabled={saleId === p.id}
                        loading={saleId === p.id}
                        title="Open flash sale (load stock into Redis)"
                        icon={<Zap className="w-4 h-4" />}
                      />
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => startEdit(p)}
                        title="Edit"
                        icon={<Pencil className="w-4 h-4" />}
                      />
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => handleDelete(p.id)}
                        title="Delete"
                        icon={<Trash2 className="w-4 h-4" />}
                        className="text-red-600 hover:bg-red-50"
                      />
                    </div>
                  </td>
                </tr>
              ))}
              {rows.length === 0 && (
                <tr>
                  <td colSpan={7} className="px-4 py-6 text-center text-slate-400">
                    No products yet. Create one above.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
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
              <h2 className="text-sm font-medium text-slate-700">Refund requests</h2>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => { void loadRefunds(); void loadOrders() }}
                className="ml-auto"
              >
                Refresh
              </Button>
            </div>
            {refundsError && <p className="text-sm text-red-600">{refundsError}</p>}
            {refundsLoading ? (
              <p className="text-sm text-slate-500">Loading refunds…</p>
            ) : refunds.length === 0 ? (
              <p className="text-sm text-slate-400">No pending refund requests.</p>
            ) : (
              <div className="bg-white border border-slate-200 rounded-lg overflow-hidden">
                <table className="w-full text-sm">
                  <thead className="bg-slate-50 text-slate-600">
                    <tr>
                      <th className="text-left px-4 py-2 font-medium">Order</th>
                      <th className="text-left px-4 py-2 font-medium">Customer</th>
                      <th className="text-left px-4 py-2 font-medium">Reason</th>
                      <th className="text-right px-4 py-2 font-medium">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {refunds.map((o) => (
                      <tr key={o.id} className="border-t border-slate-100">
                        <td className="px-4 py-2 text-slate-500">#{o.id}</td>
                        <td className="px-4 py-2 text-slate-800">{o.customerName}</td>
                        <td className="px-4 py-2 text-slate-600 max-w-xs truncate">
                          {o.refundReason || '—'}
                        </td>
                        <td className="px-4 py-2 text-right space-x-2">
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => handleApproveRefund(o.id)}
                            disabled={busyOrderId === o.id}
                            className="text-green-600"
                          >
                            {busyOrderId === o.id ? '…' : 'Approve'}
                          </Button>
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => handleDenyRefund(o.id)}
                            disabled={busyOrderId === o.id}
                            className="text-red-600"
                          >
                            {busyOrderId === o.id ? '…' : 'Deny'}
                          </Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          {/* Orders table */}
          <section className="space-y-3">
            <div className="flex items-center gap-2">
              <Package className="w-4 h-4 text-brand-600" />
              <h2 className="text-sm font-medium text-slate-700">All orders</h2>
              <label className="ml-auto flex items-center gap-1.5 text-xs text-slate-500">
                Status
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
              <p className="text-sm text-slate-500">Loading…</p>
            ) : (
              <div className="bg-white border border-slate-200 rounded-lg overflow-hidden">
                <table className="w-full text-sm">
                  <thead className="bg-slate-50 text-slate-600">
                    <tr>
                      <th className="text-left px-4 py-2 font-medium">Order</th>
                      <th className="text-left px-4 py-2 font-medium">Customer</th>
                      <th className="text-right px-4 py-2 font-medium">Total</th>
                      <th className="text-left px-4 py-2 font-medium">Status</th>
                      <th className="text-left px-4 py-2 font-medium">Tracking</th>
                      <th className="text-right px-4 py-2 font-medium">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {orders.map((o) => (
                      <tr key={o.id} className="border-t border-slate-100">
                        <td className="px-4 py-2 text-slate-500">#{o.id}</td>
                        <td className="px-4 py-2 text-slate-800">
                          {o.customerName}
                          {o.customerPhone && (
                            <span className="block text-xs text-slate-400">{o.customerPhone}</span>
                          )}
                        </td>
                        <td className="px-4 py-2 text-right text-slate-700">
                          {formatPrice(o.totalInCents)}
                        </td>
                        <td className="px-4 py-2">
                          <StatusBadge status={o.status} />
                        </td>
                        <td className="px-4 py-2 text-slate-600 font-mono text-xs">
                          {o.trackingNumber || '—'}
                        </td>
                        <td className="px-4 py-2 text-right">
                          {o.status === 'PAID' && (
                            <div className="flex items-center justify-end gap-1.5">
                              <Input
                                placeholder="Tracking #"
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
                                Ship
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
                              Deliver
                            </Button>
                          )}
                          {o.status !== 'PAID' && o.status !== 'SHIPPED' && (
                            <span className="text-xs text-slate-300">—</span>
                          )}
                        </td>
                      </tr>
                    ))}
                    {orders.length === 0 && (
                      <tr>
                        <td colSpan={6} className="px-4 py-6 text-center text-slate-400">
                          No orders{statusFilter ? ` with status ${statusFilter}` : ''}.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
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
              <h2 className="text-sm font-medium text-slate-700">Open sessions</h2>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => { void loadInbox() }}
                className="ml-auto"
              >
                Refresh
              </Button>
            </div>
            {inboxError && <p className="text-sm text-red-600">{inboxError}</p>}
            {inboxLoading ? (
              <p className="text-sm text-slate-500">Loading…</p>
            ) : inbox.length === 0 ? (
              <p className="text-sm text-slate-400">No open chat sessions.</p>
            ) : (
              <div className="bg-white border border-slate-200 rounded-lg overflow-hidden">
                <table className="w-full text-sm">
                  <thead className="bg-slate-50 text-slate-600">
                    <tr>
                      <th className="text-left px-4 py-2 font-medium">Session</th>
                      <th className="text-left px-4 py-2 font-medium">User</th>
                      <th className="text-left px-4 py-2 font-medium">Status</th>
                      <th className="text-left px-4 py-2 font-medium">Created</th>
                      <th className="text-right px-4 py-2 font-medium">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {inbox.map((s) => (
                      <tr key={s.id} className="border-t border-slate-100">
                        <td className="px-4 py-2 text-slate-500">#{s.id}</td>
                        <td className="px-4 py-2 text-slate-800">user #{s.userId}</td>
                        <td className="px-4 py-2">
                          <Badge color="green">{s.status}</Badge>
                        </td>
                        <td className="px-4 py-2 text-xs text-slate-600">
                          {s.createTime
                            ? new Date(s.createTime).toLocaleString(undefined, {
                                month: 'short',
                                day: 'numeric',
                                hour: '2-digit',
                                minute: '2-digit',
                              })
                            : '—'}
                        </td>
                        <td className="px-4 py-2 text-right">
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => setSelectedSessionId(s.id)}
                          >
                            {selectedSessionId === s.id ? 'Selected' : 'Open'}
                          </Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
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
        <h2 className="text-sm font-medium text-slate-700">Session #{sessionId}</h2>
        <span className="text-xs text-slate-400">{connected ? '已连接' : '连接中…'}</span>
      </div>
      <div className="h-80 overflow-y-auto rounded border border-slate-200 p-3 bg-slate-50">
        {historyLoading && <p className="text-sm text-slate-500">Loading history…</p>}
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
          <p className="text-sm text-slate-400 text-center">No messages yet.</p>
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
