import { useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { getProductDetail, getProduct } from '@/api/product'
import { useAuthStore } from '@/store/useAuthStore'
import { useCart } from '@/store/CartContext'
import type { ProductDetail } from '@/api/types'
import { formatPrice } from '@/lib/format'
import Button from '@/components/ui/Button'
import Input from '@/components/ui/Input'
import Badge from '@/components/ui/Badge'
import EmptyState from '@/components/ui/EmptyState'
import Spinner from '@/components/ui/Spinner'

function fmtCountdown(ms: number): string {
  if (ms <= 0) return '00:00:00'
  const s = Math.floor(ms / 1000)
  const hh = String(Math.floor(s / 3600)).padStart(2, '0')
  const mm = String(Math.floor((s % 3600) / 60)).padStart(2, '0')
  const ss = String(s % 60).padStart(2, '0')
  return `${hh}:${mm}:${ss}`
}

export default function ProductDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const token = useAuthStore((s) => s.token)
  const { add } = useCart()
  const [detail, setDetail] = useState<ProductDetail | null>(null)
  const [liveStock, setLiveStock] = useState<number | null>(null)
  const [now, setNow] = useState(() => Date.now())
  const [notFound, setNotFound] = useState(false)
  const [qty, setQty] = useState(1)

  useEffect(() => {
    setNotFound(false)
    setLiveStock(null)
    getProductDetail(Number(id))
      .then((d) => setDetail(d))
      .catch((e) => {
        // The axios interceptor (src/api/request.ts) rejects with `new Error(message)`
        // — a bare Error with no `.response` property — so checking `e.response.status`
        // is dead code. The backend's GlobalExceptionHandler returns HTTP 404 with
        // Result.fail(404, "product not found: <id>"), and the interceptor surfaces that
        // message verbatim. Match by message to detect the not-found state.
        if (/not found/i.test(e?.message ?? '')) {
          setNotFound(true)
        }
      })
  }, [id])

  // Ticking countdown + auto-flip upcoming→live. Without this, the displayed
  // countdown is frozen and the page never transitions to live stock polling
  // without a manual refresh.
  useEffect(() => {
    if (!detail?.isFlashSale || !detail?.flashSaleAt) return
    const flashStart = new Date(detail.flashSaleAt).getTime()
    if (flashStart <= Date.now()) return // already live; the stock poll handles it
    const t = setInterval(() => {
      setNow(Date.now())
      if (Date.now() >= flashStart) {
        // Sale just went live — re-fetch so isFlashUpcoming flips to isFlashLive
        // and the stock-poll effect (below) starts.
        getProductDetail(Number(id))
          .then((d) => setDetail(d))
          .catch(() => {})
      }
    }, 1000)
    return () => clearInterval(t)
  }, [detail, id])

  // Flash-sale live-stock polling (moved from the old FlashSaleCard).
  useEffect(() => {
    if (!detail?.isFlashSale || !detail?.flashSaleAt) return
    if (new Date(detail.flashSaleAt).getTime() > Date.now()) return // not started; countdown only
    let stop = false
    const poll = async () => {
      try { const p = await getProduct(detail.id); if (!stop) setLiveStock(p.stock) } catch {}
    }
    poll()
    const t = setInterval(poll, 4000)
    return () => { stop = true; clearInterval(t) }
  }, [detail])

  if (notFound) return <div className="p-8"><EmptyState title="商品不存在" /></div>
  if (!detail) return <div className="p-8 flex items-center gap-2"><Spinner /> 加载中...</div>

  const flashStart = detail.flashSaleAt ? new Date(detail.flashSaleAt).getTime() : 0
  const isFlashLive = detail.isFlashSale && flashStart > 0 && flashStart <= Date.now()
  const isFlashUpcoming = detail.isFlashSale && flashStart > Date.now()
  const [hh, mm, ss] = fmtCountdown(flashStart - now).split(':')
  const stockShown = isFlashLive ? (liveStock ?? detail.stock) : detail.stock
  const maxQty = Math.max(1, stockShown) // can't add more than available stock

  const onAddToCart = () => {
    if (!token) { navigate('/login'); return }
    if (detail.stock <= 0) return
    add(detail, Math.min(qty, maxQty))   // cartReducer caps at stock too; this is a UX guard
    setQty(1)
  }

  return (
    <div className="mx-auto max-w-4xl p-4">
      <div className="grid gap-4 sm:grid-cols-2">
        {detail.imageUrl && <img src={detail.imageUrl} alt={detail.name} loading="lazy" className="h-64 w-full rounded bg-slate-50 object-contain" />}
        <div>
          <h1 className="text-2xl font-bold">{detail.name}</h1>
          <div className="my-2 text-xl text-brand-600">{formatPrice(detail.priceInCents)}</div>
          <div className="text-sm text-slate-500">分类: {detail.category ?? '—'} · 已售 {detail.sold} · 评分 {detail.avgRating.toFixed(1)}</div>
          <div className="my-2 flex items-center gap-2"><span className="text-sm text-slate-500">库存:</span> <Badge>{stockShown}</Badge></div>
          {isFlashUpcoming && <div className="text-brand-600">秒杀即将开始 倒计时 {hh}:{mm}:{ss}</div>}
          <div className="mt-3 flex items-center gap-3">
            <label className="flex items-center gap-1.5 text-sm text-slate-600">
              数量
              <Input
                type="number"
                min={1}
                max={maxQty}
                value={qty}
                onChange={(e) => setQty(Math.min(Math.max(Number(e.target.value) || 1, 1), maxQty))}
                className="w-20"
                aria-label="购买数量"
              />
            </label>
            <Button onClick={onAddToCart}>加入购物车</Button>
          </div>
        </div>
      </div>
      <div className="mt-6">
        <h2 className="mb-2 text-lg font-semibold">商品详情</h2>
        <p className="text-slate-700">{detail.description ?? '暂无描述'}</p>
      </div>
      <div className="mt-6">
        <h2 className="mb-2 text-lg font-semibold">评价</h2>
        <p className="text-slate-500">暂无评价</p>
      </div>
    </div>
  )
}
