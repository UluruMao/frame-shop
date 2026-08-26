import { useState, type ReactNode } from 'react'
import { cn } from '@/lib/format'

export interface TabItem<K extends string> {
  key: K
  label: ReactNode
  content: ReactNode
}

export interface TabsProps<K extends string> {
  tabs: TabItem<K>[]
  /** Uncontrolled: initial active key. */
  defaultKey?: K
  /** Controlled: active key + change handler. Mutually exclusive with defaultKey. */
  activeKey?: K
  onTabChange?: (key: K) => void
  className?: string
}

/**
 * Tab switcher matching the app's nav pattern (border-b-2 active pill).
 * Uncontrolled when `defaultKey` is given; controlled when `activeKey`+`onTabChange`.
 */
export default function Tabs<K extends string>({ tabs, defaultKey, activeKey, onTabChange, className }: TabsProps<K>) {
  const [internal, setInternal] = useState<K | undefined>(defaultKey ?? tabs[0]?.key)
  const current = activeKey ?? internal ?? tabs[0]?.key
  const active = tabs.find((t) => t.key === current) ?? tabs[0]

  const select = (key: K) => {
    if (activeKey === undefined) setInternal(key)
    onTabChange?.(key)
  }

  return (
    <div className={className}>
      <div className="flex items-center gap-1 border-b border-slate-200">
        {tabs.map((tab) => {
          const isActive = tab.key === active?.key
          return (
            <button
              key={tab.key}
              onClick={() => select(tab.key)}
              className={cn(
                'px-3 py-2 text-sm border-b-2 -mb-px transition-colors',
                isActive
                  ? 'border-brand-600 text-brand-600 font-medium'
                  : 'border-transparent text-slate-600 hover:text-slate-800',
              )}
            >
              {tab.label}
            </button>
          )
        })}
      </div>
      <div className="pt-3">{active?.content}</div>
    </div>
  )
}
