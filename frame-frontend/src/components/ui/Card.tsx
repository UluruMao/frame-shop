// frame-frontend/src/components/ui/Card.tsx
import { type ReactNode } from 'react'
import { cn } from '@/lib/format'

export interface CardProps {
  children: ReactNode
  className?: string
}

/** Canonical white card shell — replaces ~15 hand-written `bg-white border border-slate-200 rounded-lg p-4`. */
export default function Card({ children, className }: CardProps) {
  return (
    <div className={cn('bg-white border border-slate-200 rounded-lg', className)}>
      {children}
    </div>
  )
}

function CardHeader({ children, className }: CardProps) {
  return <div className={cn('p-4 border-b border-slate-100', className)}>{children}</div>
}
function CardBody({ children, className }: CardProps) {
  return <div className={cn('p-4', className)}>{children}</div>
}

// Compound components — Card.Header / Card.Body.
Card.Header = CardHeader
Card.Body = CardBody
