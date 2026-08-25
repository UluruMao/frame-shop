import { cn } from '@/lib/format'

export interface SkeletonProps {
  className?: string
}

/** Animated loading placeholder block. */
export default function Skeleton({ className }: SkeletonProps) {
  return <div className={cn('animate-pulse rounded bg-slate-200', className)} />
}
