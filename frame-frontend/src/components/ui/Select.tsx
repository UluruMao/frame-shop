// frame-frontend/src/components/ui/Select.tsx
import { type SelectHTMLAttributes } from 'react'
import { cn } from '@/lib/format'

const BASE = 'w-full px-3 py-2 rounded-md border border-slate-300 text-sm focus:outline-none focus:ring-2 focus:ring-brand-500 bg-white'

export interface SelectProps extends SelectHTMLAttributes<HTMLSelectElement> {}

/** Styled select matching the Input look — replaces raw <select> in Products/Admin. */
export default function Select({ className, children, ...rest }: SelectProps) {
  return (
    <select className={cn(BASE, className)} {...rest}>
      {children}
    </select>
  )
}
