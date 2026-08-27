import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import Dashboard from '@/pages/Dashboard'

const { getAdminStats } = vi.hoisted(() => ({
  getAdminStats: vi.fn(),
}))

vi.mock('@/api/stats', () => ({ getAdminStats }))

const mockStats = {
  totalRevenueCents: 48200,
  totalOrders: 312,
  totalProducts: 6,
  refundCount: 14,
  avgRating: 4.2,
  monthlyRevenue: [],
  unitsByProduct: [{ productId: 1, name: 'Widget', units: 40 }],
  revenueTrend: [{ bucket: '2026-08-20', cents: 8100 }],
  salesShareByProduct: [{ productId: 1, name: 'Widget', units: 40 }],
}

describe('Dashboard', () => {
  beforeEach(() => {
    getAdminStats.mockReset()
    getAdminStats.mockResolvedValue(mockStats)
  })

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

describe('Dashboard analytics', () => {
  it('renders 3 charts and refetches on range change', async () => {
    render(<Dashboard />)
    await waitFor(() => expect(screen.getByText('总营收')).toBeInTheDocument())
    // bar + pie share "Widget"; line shows the trend bucket
    expect(getAdminStats).toHaveBeenCalledWith('30d', undefined, undefined)

    // The three chart sections are present
    expect(screen.getByText('商品销量')).toBeInTheDocument()
    expect(screen.getByText('营收趋势')).toBeInTheDocument()
    expect(screen.getByText('销量占比')).toBeInTheDocument()

    // Refetch on range change
    const select = screen.getByLabelText('时间范围') as HTMLSelectElement
    fireEvent.change(select, { target: { value: '7d' } })
    await waitFor(() => expect(getAdminStats).toHaveBeenCalledWith('7d', undefined, undefined))
  })
})
