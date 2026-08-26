import { Fragment, type ReactNode } from 'react'
import { cn } from '@/lib/format'

export interface TableColumn<T> {
  key: string
  header: ReactNode
  /** Custom cell renderer; defaults to `row[key]`. */
  render?: (row: T) => ReactNode
  className?: string
}

export interface TableProps<T> {
  columns: TableColumn<T>[]
  data: T[]
  /** Stable React key per row (kills the fragment-as-map-root key warning). */
  rowKey: (row: T) => string
  empty?: ReactNode
  /** Optional expanded sub-row rendered below a row (Orders line-item drawer). */
  expandableRow?: (row: T) => ReactNode
  /** The key of the currently-expanded row, or null. */
  expandedRowKey?: string | null
  className?: string
}

/**
 * Striped table matching the app's hand-written tables (bg-slate-50 header,
 * border-slate-100 rows, text-sm). `rowKey` drives React keys; `expandableRow`
 * supports the Orders sub-row drawer pattern.
 */
export default function Table<T>({ columns, data, rowKey, empty, expandableRow, expandedRowKey, className }: TableProps<T>) {
  return (
    <div className={cn('bg-white border border-slate-200 rounded-lg overflow-hidden', className)}>
      <table className="w-full text-sm">
        <thead className="bg-slate-50 text-slate-600">
          <tr>
            {columns.map((col) => (
              <th key={col.key} className={cn('text-left px-4 py-2 font-medium', col.className)}>
                {col.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {data.map((row) => {
            const key = rowKey(row)
            const expanded = expandableRow && expandedRowKey === key
            return (
              <Fragment key={key}>
                <tr className="border-t border-slate-100">
                  {columns.map((col) => (
                    <td key={col.key} className={cn('px-4 py-2', col.className)}>
                      {col.render ? col.render(row) : String((row as Record<string, unknown>)[col.key] ?? '')}
                    </td>
                  ))}
                </tr>
                {expanded && expandableRow && (
                  <tr className="bg-slate-50">
                    <td colSpan={columns.length} className="px-4 py-3">
                      {expandableRow(row)}
                    </td>
                  </tr>
                )}
              </Fragment>
            )
          })}
          {data.length === 0 && empty && (
            <tr>
              <td colSpan={columns.length} className="px-4 py-6 text-center text-slate-400">
                {empty}
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  )
}
