// frame-frontend/src/components/ui/StatusBadge.tsx
import type { OrderStatus } from '@/api/types'
import { cn } from '@/lib/format'

/**
 * The SINGLE source of the 9-status → color map. Replaces the three duplicated
 * STATUS_COLORS / ORDER_STATUS_COLORS maps in Checkout.tsx, Orders.tsx, Admin.tsx.
 * Canonical mapping (the Admin.tsx set — PAID=blue, SHIPPED=indigo, DELIVERED=green).
 */
const STATUS_COLOR: Record<OrderStatus, string> = {
  PENDING: 'bg-amber-100 text-amber-700',
  PAID: 'bg-blue-100 text-blue-700',
  SHIPPED: 'bg-indigo-100 text-indigo-700',
  DELIVERED: 'bg-green-100 text-green-700',
  COMPLETED: 'bg-slate-100 text-slate-600',
  REFUND_PENDING: 'bg-orange-100 text-orange-700',
  REFUNDED: 'bg-red-100 text-red-700',
  CANCELLED: 'bg-slate-100 text-slate-600',
  FAILED: 'bg-red-100 text-red-700',
}

export interface StatusBadgeProps {
  status: OrderStatus
  /** Optional override label (defaults to the raw status string). */
  label?: string
  className?: string
}

export default function StatusBadge({ status, label, className }: StatusBadgeProps) {
  return (
    <span className={cn('px-2 py-0.5 rounded text-xs font-medium', STATUS_COLOR[status], className)}>
      {label ?? status}
    </span>
  )
}
