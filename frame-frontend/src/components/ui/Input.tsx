// frame-frontend/src/components/ui/Input.tsx
import { type InputHTMLAttributes, type TextareaHTMLAttributes, forwardRef } from 'react'
import { cn } from '@/lib/format'

const BASE = 'w-full px-3 py-2 rounded-md border border-slate-300 text-sm focus:outline-none focus:ring-2 focus:ring-brand-500'

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  error?: string
}

/** Canonical text input — the single focus-ring + border style every page used to rewrite. */
const Input = forwardRef<HTMLInputElement, InputProps>(
  ({ className, error, ...rest }, ref) => (
    <input ref={ref} className={cn(BASE, error && 'border-red-500', className)} {...rest} />
  ),
)
Input.displayName = 'Input'
export default Input

export interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {}

/** Canonical textarea sharing the Input style. */
export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(
  ({ className, ...rest }, ref) => (
    <textarea ref={ref} className={cn(BASE, className)} {...rest} />
  ),
)
Textarea.displayName = 'Textarea'
