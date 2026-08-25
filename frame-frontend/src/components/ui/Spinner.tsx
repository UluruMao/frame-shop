import { Loader2 } from 'lucide-react'
import { cn } from '@/lib/format'

export interface SpinnerProps {
  className?: string
}

/** Loading spinner — replaces `加载中…` text where a spinner reads better. */
export default function Spinner({ className }: SpinnerProps) {
  return <Loader2 className={cn('w-5 h-5 text-brand-600 animate-spin', className)} />
}
