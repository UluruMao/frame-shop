import { useEffect, useState, type FormEvent } from 'react'
import { Shield, Zap, Pencil, Trash2, Plus, Loader2 } from 'lucide-react'
import {
  pageProducts,
  createProduct,
  updateProduct,
  deleteProduct,
  openFlashSale,
} from '@/api/product'
import type { Product } from '@/api/types'
import { formatPrice } from './Products'

interface FormState {
  name: string
  priceInCents: string
  stock: string
  category: string
  description: string
  isFlashSale: boolean
}

const EMPTY_FORM: FormState = {
  name: '',
  priceInCents: '',
  stock: '',
  category: '',
  description: '',
  isFlashSale: false,
}

/**
 * Admin page: product CRUD + "open flash sale" (loads stock into Redis).
 * The backend gates /products mutations and /admin/sales to ADMIN role;
 * this page is only routed to admins. Non-admins hitting it get a notice.
 */
export default function Admin() {
  const [rows, setRows] = useState<Product[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [form, setForm] = useState<FormState>(EMPTY_FORM)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [saving, setSaving] = useState(false)
  const [saleId, setSaleId] = useState<number | null>(null)

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
    void load()
  }, [])

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

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <Shield className="w-5 h-5 text-brand-600" />
        <h1 className="text-xl font-semibold text-slate-800">Admin</h1>
        <span className="text-sm text-slate-400">({total} products)</span>
      </div>

      {error && <p className="text-sm text-red-600">{error}</p>}

      {/* Create / edit form */}
      <form onSubmit={handleSubmit} className="bg-white border border-slate-200 rounded-lg p-4 space-y-3">
        <h2 className="text-sm font-medium text-slate-700">
          {editingId !== null ? `Edit product #${editingId}` : 'New product'}
        </h2>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <input
            placeholder="Name"
            className="px-3 py-2 rounded-md border border-slate-300 text-sm focus:outline-none focus:ring-2 focus:ring-brand-500"
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            required
          />
          <input
            placeholder="Price (cents)"
            type="number"
            min={0}
            className="px-3 py-2 rounded-md border border-slate-300 text-sm focus:outline-none focus:ring-2 focus:ring-brand-500"
            value={form.priceInCents}
            onChange={(e) => setForm({ ...form, priceInCents: e.target.value })}
            required
          />
          <input
            placeholder="Stock"
            type="number"
            min={0}
            className="px-3 py-2 rounded-md border border-slate-300 text-sm focus:outline-none focus:ring-2 focus:ring-brand-500"
            value={form.stock}
            onChange={(e) => setForm({ ...form, stock: e.target.value })}
            required
          />
          <input
            placeholder="Category (optional)"
            className="px-3 py-2 rounded-md border border-slate-300 text-sm focus:outline-none focus:ring-2 focus:ring-brand-500"
            value={form.category}
            onChange={(e) => setForm({ ...form, category: e.target.value })}
          />
        </div>
        <textarea
          placeholder="Description (optional)"
          className="w-full px-3 py-2 rounded-md border border-slate-300 text-sm focus:outline-none focus:ring-2 focus:ring-brand-500"
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
        <div className="flex gap-2">
          <button
            type="submit"
            disabled={saving}
            className="px-4 py-2 rounded-md bg-brand-600 text-white text-sm font-medium hover:bg-brand-700 disabled:opacity-60 flex items-center gap-1.5"
          >
            {saving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Plus className="w-4 h-4" />}
            {editingId !== null ? 'Update' : 'Create'}
          </button>
          {editingId !== null && (
            <button
              type="button"
              onClick={resetForm}
              className="px-4 py-2 rounded-md bg-slate-100 text-slate-700 text-sm font-medium hover:bg-slate-200"
            >
              Cancel edit
            </button>
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
                      <span className="px-1.5 py-0.5 rounded bg-amber-100 text-amber-700 text-xs font-medium">
                        Yes
                      </span>
                    ) : (
                      <span className="text-xs text-slate-300">No</span>
                    )}
                  </td>
                  <td className="px-4 py-2 text-right">
                    <div className="flex items-center justify-end gap-1">
                      <button
                        onClick={() => handleOpenSale(p.id)}
                        disabled={saleId === p.id}
                        title="Open flash sale (load stock into Redis)"
                        className="p-1.5 rounded-md text-amber-600 hover:bg-amber-50 disabled:opacity-50"
                      >
                        {saleId === p.id ? (
                          <Loader2 className="w-4 h-4 animate-spin" />
                        ) : (
                          <Zap className="w-4 h-4" />
                        )}
                      </button>
                      <button
                        onClick={() => startEdit(p)}
                        title="Edit"
                        className="p-1.5 rounded-md text-slate-600 hover:bg-slate-100"
                      >
                        <Pencil className="w-4 h-4" />
                      </button>
                      <button
                        onClick={() => handleDelete(p.id)}
                        title="Delete"
                        className="p-1.5 rounded-md text-red-600 hover:bg-red-50"
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
              {rows.length === 0 && (
                <tr>
                  <td colSpan={6} className="px-4 py-6 text-center text-slate-400">
                    No products yet. Create one above.
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
