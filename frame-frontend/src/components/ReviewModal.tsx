import { useState } from 'react'
import { X } from 'lucide-react'
import StarRating from './StarRating'
import { createReview, updateReview, uploadReviewImage } from '@/api/review'
import type { Review } from '@/api/types'

interface ReviewModalProps {
  orderId: number
  productId: number
  existing: Review | null
  onClose: () => void
  onSuccess: () => void
}

const MAX_IMAGES = 9

export default function ReviewModal({ orderId, productId, existing, onClose, onSuccess }: ReviewModalProps) {
  const [quality, setQuality] = useState(existing?.qualityStars ?? 5)
  const [logistics, setLogistics] = useState(existing?.logisticsStars ?? 5)
  const [service, setService] = useState(existing?.serviceStars ?? 5)
  const [content, setContent] = useState(existing?.content ?? '')
  const [imageUrls, setImageUrls] = useState<string[]>(existing?.imageUrls ?? [])
  const [anonymous, setAnonymous] = useState(existing?.isAnonymous ?? false)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)

  async function onPickImages(files: FileList | null) {
    if (!files) return
    const room = MAX_IMAGES - imageUrls.length
    const picked = Array.from(files).slice(0, room)
    try {
      const urls = await Promise.all(picked.map((f) => uploadReviewImage(f)))
      setImageUrls((prev) => [...prev, ...urls])
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'image upload failed')
    }
  }

  async function submit() {
    setBusy(true); setErr(null)
    try {
      const payload = { qualityStars: quality, logisticsStars: logistics, serviceStars: service,
        content: content || undefined, imageUrls, isAnonymous: anonymous }
      if (existing) {
        await updateReview(existing.id, payload)
      } else {
        await createReview(orderId, { orderId, productId, ...payload })
      }
      onSuccess()
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'submit failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="w-full max-w-md rounded-lg bg-white p-5 shadow-lg">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-lg font-bold">{existing ? '编辑评价' : '写评价'}</h2>
          <button onClick={onClose}><X className="h-5 w-5 text-slate-400" /></button>
        </div>
        <div className="space-y-3">
          <StarRating label="商品质量" value={quality} onChange={setQuality} />
          <StarRating label="物流速度" value={logistics} onChange={setLogistics} />
          <StarRating label="客服态度" value={service} onChange={setService} />
          <textarea
            className="w-full border rounded p-2 text-sm"
            rows={3}
            placeholder="图文评价（可选）"
            value={content}
            onChange={(e) => setContent(e.target.value)}
          />
          <div>
            <input
              type="file"
              accept=".jpg,.jpeg,.png"
              multiple
              onChange={(e) => onPickImages(e.target.files)}
              disabled={imageUrls.length >= MAX_IMAGES}
            />
            <div className="mt-1 flex flex-wrap gap-1">
              {imageUrls.map((u, i) => (
                <div key={i} className="relative">
                  <img src={u} alt="" className="h-12 w-12 rounded object-cover" />
                  <button
                    type="button"
                    className="absolute -right-1 -top-1 rounded-full bg-red-600 px-1 text-xs text-white"
                    onClick={() => setImageUrls((p) => p.filter((_, j) => j !== i))}
                  >×</button>
                </div>
              ))}
            </div>
          </div>
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={anonymous} onChange={(e) => setAnonymous(e.target.checked)} />
            匿名评价
          </label>
        </div>
        {err && <p className="mt-2 text-sm text-red-600">{err}</p>}
        <div className="mt-4 flex justify-end gap-2">
          <button onClick={onClose} className="rounded border px-3 py-1.5 text-sm">取消</button>
          <button
            onClick={submit}
            disabled={busy}
            className="rounded bg-brand-600 px-3 py-1.5 text-sm text-white disabled:opacity-50"
          >
            {busy ? '提交中…' : '提交评价'}
          </button>
        </div>
      </div>
    </div>
  )
}
