// frame-frontend/src/components/ui/Badge.tsx
import { type ReactNode } from 'react'
import { cn } from '@/lib/format'

export type BadgeColor = 'amber' | 'green' | 'red' | 'slate' | 'indigo' | 'blue' | 'orange'

const COLOR: Record<BadgeColor, string> = {
  amber: 'bg-amber-100 text-amber-700',
  green: 'bg-green-100 text-green-700',
  red: 'bg-red-100 text-red-700',
  slate: 'bg-slate-100 text-slate-600',
  indigo: 'bg-indigo-100 text-indigo-700',
  blue: 'bg-blue-100 text-blue-700',
  orange: 'bg-orange-100 text-orange-700',
}

export interface BadgeProps {
  color?: BadgeColor
  children: ReactNode
  className?: string
}

/** Generic colored pill. Use for ad-hoc badges; use StatusBadge for order statuses. */
export default function Badge({ color = 'slate', children, className }: BadgeProps) {
  return (
    <span className={cn('px-2 py-0.5 rounded text-xs font-medium', COLOR[color], className)}>
      {children}
    </span>
  )
}
