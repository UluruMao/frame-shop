import { useState } from 'react'
import StarRating from './StarRating'
import Modal from './ui/Modal'
import { createReview, updateReview, uploadReviewImage } from '@/api/review'
import type { Review } from '@/api/types'
import { Textarea } from './ui/Input'
import Button from './ui/Button'

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
      setErr(e instanceof Error ? e.message : '图片上传失败')
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
      setErr(e instanceof Error ? e.message : '提交失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      open={true}
      onClose={onClose}
      title={existing ? '编辑评价' : '写评价'}
      footer={
        <>
          {err && <p className="mr-auto text-sm text-red-600">{err}</p>}
          <Button variant="secondary" onClick={onClose}>取消</Button>
          <Button onClick={submit} loading={busy}>
            {busy ? '提交中…' : '提交评价'}
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <StarRating label="商品质量" value={quality} onChange={setQuality} />
        <StarRating label="物流速度" value={logistics} onChange={setLogistics} />
        <StarRating label="客服态度" value={service} onChange={setService} />
        <Textarea
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
                <img src={u} alt="" loading="lazy" className="h-12 w-12 rounded object-cover" />
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
    </Modal>
  )
}
