import { useEffect, useState, type FormEvent } from 'react'
import { Shield, Zap, Pencil, Trash2, Plus } from 'lucide-react'
import {
  pageProducts,
  createProduct,
  updateProduct,
  deleteProduct,
  openFlashSale,
} from '@/api/product'
import type { Product } from '@/api/types'
import { formatPrice } from '@/lib/format'
import { useIsAdmin } from '@/store/useAuthStore'
import Button from '@/components/ui/Button'
import Input, { Textarea } from '@/components/ui/Input'
import Badge from '@/components/ui/Badge'
import Table from '@/components/ui/Table'
import Modal from '@/components/ui/Modal'

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
  discountPercent: string
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
  discountPercent: '',
}

/**
 * Admin product CRUD + "open flash sale" (loads stock into Redis).
 * The backend gates /products mutations and /admin/sales to ADMIN role;
 * RequireAdmin guards the /admin/* route tree so only admins reach this page.
 * A local useIsAdmin gate remains as a defense-in-depth check for direct renders.
 */
export default function AdminProducts() {
  const isAdmin = useIsAdmin()
  const [rows, setRows] = useState<Product[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [form, setForm] = useState<FormState>(EMPTY_FORM)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [saving, setSaving] = useState(false)
  const [saleId, setSaleId] = useState<number | null>(null)
  /** Two-step flash-confirm Modal: "创建"/"更新" opens this; "确认" runs doSave. */
  const [confirmFlash, setConfirmFlash] = useState(false)

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
      discountPercent: p.discountPercent != null ? String(p.discountPercent) : '',
    })
  }

  async function doSave() {
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
      discountPercent: form.isFlashSale && form.discountPercent ? Number(form.discountPercent) : undefined,
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

  /** Opens the two-step confirm Modal; the actual save runs in `doSave` on confirm. */
  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setConfirmFlash(true)
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

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <Pencil className="w-5 h-5 text-brand-600" />
        <h1 className="text-xl font-semibold text-slate-800">商品管理</h1>
        <span className="text-sm text-slate-400">({total} 件商品)</span>
      </div>

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
        {form.isFlashSale && (
          <label className="flex flex-col gap-1 text-sm text-slate-600">
            <span className="text-xs text-slate-500">秒杀折扣 (%)</span>
            <Input
              type="number"
              min={1} max={99}
              placeholder="秒杀折扣 (%)"
              value={form.discountPercent}
              onChange={(e) => setForm({ ...form, discountPercent: e.target.value })}
            />
            {form.discountPercent && Number(form.discountPercent) > 0 && Number(form.priceInCents) > 0 && (
              <span className="text-xs text-brand-600">
                秒杀价 ¥{((Number(form.priceInCents) * (100 - Number(form.discountPercent)) / 100) / 100).toFixed(2)}
              </span>
            )}
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

      {/* Two-step flash-confirm: submit opens this Modal; "确认" runs the save. */}
      <Modal open={confirmFlash} onClose={() => setConfirmFlash(false)} title="确认秒杀折扣">
        <p className="text-sm text-slate-600">
          确认秒杀折扣为 {form.discountPercent}%？
          {form.priceInCents && ` 秒杀价 ¥${((Number(form.priceInCents) * (100 - Number(form.discountPercent)) / 100) / 100).toFixed(2)}。`}
        </p>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => setConfirmFlash(false)}>取消</Button>
          <Button variant="primary" onClick={() => { void doSave(); setConfirmFlash(false) }}>确认</Button>
        </div>
      </Modal>

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
    </div>
  )
}
