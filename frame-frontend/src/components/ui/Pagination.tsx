import { cn } from '@/lib/format'

export interface PaginationProps {
  current: number
  size: number
  total: number
  onChange: (page: number) => void
  className?: string
}

/** Page-number pagination with prev/next + Chinese labels. */
export default function Pagination({ current, size, total, onChange, className }: PaginationProps) {
  const totalPages = Math.max(1, Math.ceil(total / size))
  if (totalPages <= 1) return null
  const atFirst = current <= 1
  const atLast = current >= totalPages

  // Window of page numbers around current.
  const pages: number[] = []
  const start = Math.max(1, current - 2)
  const end = Math.min(totalPages, current + 2)
  for (let p = start; p <= end; p++) pages.push(p)

  return (
    <div className={cn('mt-4 flex items-center gap-2', className)}>
      <button
        disabled={atFirst}
        onClick={() => onChange(current - 1)}
        className="border px-3 py-1 rounded text-sm disabled:opacity-50 disabled:cursor-not-allowed"
      >
        上一页
      </button>
      {pages.map((p) => (
        <button
          key={p}
          onClick={() => onChange(p)}
          className={cn(
            'border px-3 py-1 rounded text-sm',
            p === current ? 'bg-brand-600 text-white border-brand-600' : 'text-slate-600',
          )}
        >
          {p}
        </button>
      ))}
      <span className="text-xs text-slate-500">共 {totalPages} 页</span>
      <button
        disabled={atLast}
        onClick={() => onChange(current + 1)}
        className="border px-3 py-1 rounded text-sm disabled:opacity-50 disabled:cursor-not-allowed"
      >
        下一页
      </button>
    </div>
  )
}
