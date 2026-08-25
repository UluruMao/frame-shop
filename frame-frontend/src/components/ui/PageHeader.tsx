import { type ReactNode } from 'react'
import { cn } from '@/lib/format'

export interface PageHeaderProps {
  title: ReactNode
  icon?: ReactNode
  count?: ReactNode
  className?: string
}

/** Icon + title + optional count — the header pattern repeated in Checkout/Orders/Coins/Profile/Admin. */
export default function PageHeader({ title, icon, count, className }: PageHeaderProps) {
  return (
    <div className={cn('flex items-center gap-2', className)}>
      {icon}
      <h1 className="text-xl font-semibold text-slate-800">{title}</h1>
      {count != null && <span className="text-sm text-slate-400">({count})</span>}
    </div>
  )
}
