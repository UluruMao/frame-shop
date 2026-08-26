import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import Coins from '@/pages/Coins'

vi.mock('@/api/coin', () => ({
  getCoinBalance: vi.fn(),
  getCoinHistory: vi.fn(),
}))
import { getCoinBalance, getCoinHistory } from '@/api/coin'

describe('Coins', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders balance card', async () => {
    ;(getCoinBalance as any).mockResolvedValue({ balance: 300, claimedToday: true, expiringSoon: 0 })
    ;(getCoinHistory as any).mockResolvedValue({ total: 0, current: 1, size: 20, records: [] })
    render(
      <MemoryRouter>
        <Coins />
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getByText(/300/)).toBeInTheDocument())
    expect(screen.getByText(/今日已领取/)).toBeInTheDocument()
  })

  it('renders history rows', async () => {
    ;(getCoinBalance as any).mockResolvedValue({ balance: 300, claimedToday: false, expiringSoon: 0 })
    ;(getCoinHistory as any).mockResolvedValue({
      total: 1,
      current: 1,
      size: 20,
      records: [
        { id: 1, amount: 100, source: 'DAILY_LOGIN', createTime: '2026-08-21 10:00:00', balanceAfter: 100 },
      ],
    })
    render(
      <MemoryRouter>
        <Coins />
      </MemoryRouter>,
    )
    // The component maps DAILY_LOGIN -> "每日登录" via SOURCE_LABEL, so the raw
    // enum string is NOT rendered. Assert the translated label instead.
    await waitFor(() => expect(screen.getByText('每日登录')).toBeInTheDocument())
    expect(screen.getByText('+100')).toBeInTheDocument()
  })

  it('renders Chinese labels (regression guard)', async () => {
    ;(getCoinBalance as any).mockResolvedValue({ balance: 300, claimedToday: true, expiringSoon: 0 })
    ;(getCoinHistory as any).mockResolvedValue({ total: 0, current: 1, size: 20, records: [] })
    render(
      <MemoryRouter>
        <Coins />
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getByText('300')).toBeInTheDocument())
    // Page heading + balance + history-section heading must stay Chinese.
    expect(screen.getByText('我的金币')).toBeInTheDocument()
    expect(screen.getByText('金币明细')).toBeInTheDocument()
  })
})
