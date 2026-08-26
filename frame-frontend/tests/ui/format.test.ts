// frame-frontend/tests/ui/format.test.ts
import { describe, it, expect } from 'vitest'
import { formatPrice, cn } from '@/lib/format'

describe('formatPrice', () => {
  it('formats integer cents as ¥yuan.cents', () => {
    expect(formatPrice(1299)).toBe('¥12.99')
    expect(formatPrice(0)).toBe('¥0.00')
    expect(formatPrice(5)).toBe('¥0.05')
    expect(formatPrice(100)).toBe('¥1.00')
  })
  it('handles large values', () => {
    expect(formatPrice(123456789)).toBe('¥1,234,567.89')
  })
  it('returns ¥0.00 for NaN, negative, and non-finite input', () => {
    expect(formatPrice(NaN)).toBe('¥0.00')
    expect(formatPrice(-1299)).toBe('¥0.00')
    expect(formatPrice(Number.POSITIVE_INFINITY)).toBe('¥0.00')
  })
})

describe('cn', () => {
  it('joins truthy string classes with a space', () => {
    expect(cn('a', 'b', 'c')).toBe('a b c')
  })
  it('drops false / null / undefined', () => {
    expect(cn('a', false, null, undefined, 'b')).toBe('a b')
  })
  it('drops empty strings', () => {
    expect(cn('a', '', 'b')).toBe('a b')
  })
})
