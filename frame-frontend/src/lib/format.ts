// frame-frontend/src/lib/format.ts

/**
 * Format integer cents as a CNY string: 1299 → "¥12.99".
 * Canonical money formatter for the whole app — CNY (¥), per user decision.
 * Replaces what pages used to inline as `¥{(priceInCents / 100).toFixed(2)}`.
 */
export function formatPrice(priceInCents: number): string {
  // Guard: callers feed real integer cents, but clamp bad input (NaN / negative /
  // non-finite / float) to ¥0.00 rather than rendering "¥NaN" or negative amounts.
  if (!Number.isFinite(priceInCents) || priceInCents < 0) return '¥0.00'
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

/**
 * Generate an RFC4122-v4-style UUID string.
 *
 * Uses crypto.randomUUID() when available (secure contexts: HTTPS or
 * localhost), otherwise falls back to a Math.random-based v4 generator.
 * The fallback is required because the cloud deployment serves over plain
 * HTTP (http://172.29.231.145), where crypto.randomUUID is undefined —
 * without this, checkout throws "crypto.randomUUID is not a function".
 * Used for the checkout idempotency key.
 */
export function uuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  // Math.random fallback (non-crypto-secure, fine for an idempotency key).
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}
