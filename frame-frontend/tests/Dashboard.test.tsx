import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import Dashboard from '@/pages/Dashboard'

vi.mock('@/api/stats', () => ({
  getAdminStats: vi.fn().mockResolvedValue({
    totalRevenueCents: 48200,
    totalOrders: 312,
    totalProducts: 6,
    refundCount: 14,
    avgRating: 4.2,
    monthlyRevenue: [{ month: '2026-03', revenueCents: 8100 }],
  }),
}))

describe('Dashboard', () => {
  it('renders the 5 stat tiles from the API', async () => {
    render(<Dashboard />)
    await waitFor(() => expect(screen.getByText('¥482.00')).toBeInTheDocument())
    expect(screen.getByText('312')).toBeInTheDocument()
    expect(screen.getByText('6')).toBeInTheDocument()
    // refundRate = 14/312 ≈ 4.49%
    expect(screen.getByText(/4\.49%/)).toBeInTheDocument()
    expect(screen.getByText('4.2')).toBeInTheDocument()
  })

  it('renders Chinese tile labels', async () => {
    render(<Dashboard />)
    await waitFor(() => expect(screen.getByText('总营收')).toBeInTheDocument())
    expect(screen.getByText('总订单')).toBeInTheDocument()
    expect(screen.getByText('商品数')).toBeInTheDocument()
    expect(screen.getByText('退款率')).toBeInTheDocument()
    expect(screen.getByText('平均评分')).toBeInTheDocument()
  })
})
