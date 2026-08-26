// frame-frontend/tests/ui/StatusBadge.test.tsx
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import StatusBadge from '@/components/ui/StatusBadge'
import type { OrderStatus } from '@/api/types'

// Each [OrderStatus, Chinese default label, canonical color class].
const CASES: Array<[OrderStatus, string, string]> = [
  ['PENDING', '待付款', 'bg-amber-100'],
  ['PAID', '已付款', 'bg-blue-100'],
  ['SHIPPED', '已发货', 'bg-indigo-100'],
  ['DELIVERED', '已签收', 'bg-green-100'],
  ['COMPLETED', '已完成', 'bg-slate-100'],
  ['REFUND_PENDING', '退款处理中', 'bg-orange-100'],
  ['REFUNDED', '已退款', 'bg-red-100'],
  ['CANCELLED', '已取消', 'bg-slate-100'],
  ['FAILED', '失败', 'bg-red-100'],
]

describe('StatusBadge', () => {
  it.each(CASES)('renders %s with canonical color class %s and Chinese label %s', (status, zhLabel, cls) => {
    render(<StatusBadge status={status} />)
    expect(screen.getByText(zhLabel)).toHaveClass(cls)
  })

  it('renders Chinese label by default for known statuses', () => {
    render(<StatusBadge status="PENDING" />)
    expect(screen.getByText('待付款')).toBeInTheDocument()
  })
  it('still honors an explicit label override', () => {
    render(<StatusBadge status="PAID" label="已付款" />)
    expect(screen.getByText('已付款')).toBeInTheDocument()
  })
})
