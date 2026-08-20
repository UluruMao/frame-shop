import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import ReviewModal from '@/components/ReviewModal'

vi.mock('@/api/review', () => ({
  createReview: vi.fn(),
  updateReview: vi.fn(),
  uploadReviewImage: vi.fn(),
  listOrderReviews: vi.fn(),
  listUnreviewedOrders: vi.fn(),
}))
import { createReview, updateReview } from '@/api/review'
import type { Review } from '@/api/types'

function existing(): Review {
  return {
    id: 99, orderId: 1, productId: 100, productName: 'Widget',
    qualityStars: 3, logisticsStars: 3, serviceStars: 3,
    content: 'old', imageUrls: [], isAnonymous: false, edited: false,
  }
}

describe('ReviewModal', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders 3 star rows + submit; create calls createReview', async () => {
    const onCreate = vi.fn()
    ;(createReview as any).mockResolvedValue({ id: 1 })
    render(<ReviewModal orderId={1} productId={100} existing={null} onClose={() => {}} onSuccess={onCreate} />)
    expect(screen.getAllByRole('button', { name: /star/i }).length).toBeGreaterThanOrEqual(15) // 3 rows × 5
    fireEvent.click(screen.getByText('提交评价'))
    await waitFor(() => expect(createReview).toHaveBeenCalledTimes(1))
    expect(onCreate).toHaveBeenCalled()
  })

  it('editing prefills existing values and calls updateReview', async () => {
    const onUpdate = vi.fn()
    ;(updateReview as any).mockResolvedValue({ id: 99 })
    render(<ReviewModal orderId={1} productId={100} existing={existing()} onClose={() => {}} onSuccess={onUpdate} />)
    fireEvent.click(screen.getByText('提交评价'))
    await waitFor(() => expect(updateReview).toHaveBeenCalledWith(99, expect.any(Object)))
    expect(onUpdate).toHaveBeenCalled()
  })

  it('close button calls onClose', () => {
    const onClose = vi.fn()
    render(<ReviewModal orderId={1} productId={100} existing={null} onClose={onClose} onSuccess={() => {}} />)
    fireEvent.click(screen.getByText('取消'))
    expect(onClose).toHaveBeenCalled()
  })
})
