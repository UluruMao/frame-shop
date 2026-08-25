// frame-frontend/src/lib/format.ts

/**
 * Format integer cents as a CNY string: 1299 → "¥12.99".
 * Canonical money formatter for the whole app — CNY (¥), per user decision.
 * Replaces what pages used to inline as `¥{(priceInCents / 100).toFixed(2)}`.
 */
export function formatPrice(priceInCents: number): string {
  return `¥${(priceInCents / 100).toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`
}

/**
 * Join conditional class strings. Falsy (false/null/undefined/'') entries
 * are dropped. A trivial, dependency-free alternative to clsx — no runtime
 * dep added. Used so components can write `cn('base', isActive && 'active')`.
 */
export function cn(...classes: Array<string | false | null | undefined>): string {
  return classes.filter(Boolean).join(' ')
}
