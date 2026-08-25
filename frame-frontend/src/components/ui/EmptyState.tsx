import { type ReactNode } from 'react'

export interface EmptyStateProps {
  title: string
  icon?: ReactNode
  action?: ReactNode
}

/** Icon + message + optional CTA — replaces bare `暂无数据` text. */
export default function EmptyState({ title, icon, action }: EmptyStateProps) {
  return (
    <div className="flex flex-col items-center gap-2 py-10 text-center">
      {icon && <div className="text-slate-300">{icon}</div>}
      <p className="text-sm text-slate-500">{title}</p>
      {action}
    </div>
  )
}
