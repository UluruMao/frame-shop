// frame-frontend/src/components/ui/Field.tsx
import { type ReactNode } from 'react'

export interface FieldProps {
  label: string
  id?: string
  htmlFor?: string
  children: ReactNode
  hint?: string
}

/** Label + control wrapper. Renders <label> + children; pairs with Input/Select. */
export default function Field({ label, id, htmlFor, children, hint }: FieldProps) {
  const target = htmlFor ?? id
  return (
    <div className="space-y-1.5">
      <label className="block text-sm text-slate-600" htmlFor={target}>
        {label}
      </label>
      {children}
      {hint && <p className="text-xs text-slate-500">{hint}</p>}
    </div>
  )
}
