// frame-frontend/tests/ui/StatusBadge.test.tsx
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import StatusBadge from '@/components/ui/StatusBadge'
import type { OrderStatus } from '@/api/types'

const CASES: Array<[OrderStatus, string]> = [
  ['PENDING', 'bg-amber-100'],
  ['PAID', 'bg-blue-100'],
  ['SHIPPED', 'bg-indigo-100'],
  ['DELIVERED', 'bg-green-100'],
  ['COMPLETED', 'bg-slate-100'],
  ['REFUND_PENDING', 'bg-orange-100'],
  ['REFUNDED', 'bg-red-100'],
  ['CANCELLED', 'bg-slate-100'],
  ['FAILED', 'bg-red-100'],
]

describe('StatusBadge', () => {
  it.each(CASES)('renders %s with canonical color class %s', (status, cls) => {
    render(<StatusBadge status={status} />)
    expect(screen.getByText(status)).toHaveClass(cls)
  })
})
