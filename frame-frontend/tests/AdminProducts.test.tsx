import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import Admin from '@/pages/Admin'

vi.mock('@/store/useAuthStore', () => ({
  useIsAdmin: () => true,
  useAuthStore: () => ({ user: { role: 'ADMIN' } }),
}))
vi.mock('@/api/product', () => ({
  pageProducts: vi.fn().mockResolvedValue({ records: [], total: 0 }),
  createProduct: vi.fn().mockResolvedValue({}),
  updateProduct: vi.fn().mockResolvedValue({}),
  deleteProduct: vi.fn().mockResolvedValue({}),
  openFlashSale: vi.fn().mockResolvedValue({}),
}))

describe('Admin product flash percentage', () => {
  it('shows flash preview and gates submit behind two-step confirm', async () => {
    render(<Admin />)
    await waitFor(() => expect(screen.getByPlaceholderText('名称')).toBeInTheDocument())

    fireEvent.change(screen.getByPlaceholderText('名称'), { target: { value: 'Item' } })
    fireEvent.change(screen.getByPlaceholderText('价格 (分)'), { target: { value: '10000' } })
    // stock is `required` on the form — fill it so jsdom constraint validation lets submit through.
    fireEvent.change(screen.getByPlaceholderText('库存'), { target: { value: '5' } })
    fireEvent.click(screen.getByLabelText('秒杀商品'))
    fireEvent.change(screen.getByPlaceholderText('秒杀折扣 (%)'), { target: { value: '30' } })

    // preview shows discounted price ¥70.00
    expect(screen.getByText(/70\.00/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '创建' }))
    // first confirm step
    expect(screen.getByText(/确认秒杀折扣为 30%/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '确认' }))

    const { createProduct } = await import('@/api/product')
    await waitFor(() => expect(createProduct).toHaveBeenCalledWith(
      expect.objectContaining({ discountPercent: 30, isFlashSale: true }),
    ))
  })
})
