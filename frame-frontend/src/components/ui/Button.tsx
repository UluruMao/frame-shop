// frame-frontend/src/components/ui/Button.tsx
import { type ButtonHTMLAttributes, type ReactNode } from 'react'
import { Loader2 } from 'lucide-react'
import { cn } from '@/lib/format'

export type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'ghost' | 'success'
export type ButtonSize = 'sm' | 'md'

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: ButtonSize
  loading?: boolean
  icon?: ReactNode
}

const VARIANT: Record<ButtonVariant, string> = {
  primary: 'bg-brand-600 text-white hover:bg-brand-700',
  secondary: 'bg-slate-100 text-slate-700 hover:bg-slate-200',
  danger: 'bg-red-600 text-white hover:bg-red-700',
  ghost: 'text-slate-600 hover:bg-slate-100',
  success: 'bg-green-600 text-white hover:bg-green-700',
}

const SIZE: Record<ButtonSize, string> = {
  sm: 'px-2.5 py-1 text-xs',
  md: 'px-4 py-2 text-sm',
}

/**
 * The one button for the whole app. Owns the brand accent + hover + rounded-md
 * + disabled styles so pages stop hand-writing `bg-brand-600 px-4 py-2 text-white`.
 * `loading` disables the button and swaps the icon for a spinner.
 */
export default function Button({
  variant = 'primary',
  size = 'md',
  loading = false,
  icon,
  className,
  children,
  disabled,
  ...rest
}: ButtonProps) {
  return (
    <button
      className={cn(
        'inline-flex items-center justify-center gap-1.5 rounded-md font-medium transition-colors disabled:opacity-60 disabled:cursor-not-allowed',
        VARIANT[variant],
        SIZE[size],
        className,
      )}
      disabled={disabled || loading}
      {...rest}
    >
      {loading ? <Loader2 className="w-4 h-4 animate-spin" /> : icon}
      {children}
    </button>
  )
}
