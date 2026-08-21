import { describe, it, expect } from 'vitest'
import {
  cartReducer,
  cartItemCount,
  cartTotalCents,
  type CartState,
} from '@/store/cartReducer'
import type { Product } from '@/api/types'

function product(overrides: Partial<Product> = {}): Product {
  return {
    id: 1,
    name: 'Widget',
    priceInCents: 1000,
    stock: 3,
    ...overrides,
  }
}

describe('cartReducer', () => {
  it('add inserts a new line capped at product stock', () => {
    const state = cartReducer(
      { items: [] },
      { type: 'add', product: product({ id: 1, stock: 3 }), quantity: 10 },
    )
    expect(state.items).toHaveLength(1)
    expect(state.items[0].quantity).toBe(3) // capped at stock
    expect(state.items[0].productId).toBe(1)
  })

  it('add aggregates quantity into an existing line, capped at stock', () => {
    const state: CartState = {
      items: [{ productId: 1, name: 'Widget', priceInCents: 1000, quantity: 2, stock: 3, isFlashSale: false }],
    }
    const next = cartReducer(state, { type: 'add', product: product({ id: 1, stock: 3 }), quantity: 5 })
    expect(next.items[0].quantity).toBe(3) // 2+5=7 capped to 3
  })

  it('add ignores a sold-out product (stock 0)', () => {
    const state = cartReducer(
      { items: [] },
      { type: 'add', product: product({ id: 1, stock: 0 }), quantity: 1 },
    )
    expect(state.items).toHaveLength(0)
  })

  it('setQuantity clamps to [0, stock] and removes the line at 0', () => {
    const state: CartState = {
      items: [{ productId: 1, name: 'Widget', priceInCents: 1000, quantity: 2, stock: 5, isFlashSale: false }],
    }
    const clamped = cartReducer(state, { type: 'setQuantity', productId: 1, quantity: 99 })
    expect(clamped.items[0].quantity).toBe(5) // clamped to stock

    const zeroed = cartReducer(state, { type: 'setQuantity', productId: 1, quantity: 0 })
    expect(zeroed.items).toHaveLength(0) // removed
  })

  it('setQuantity is a no-op for a product not in the cart', () => {
    const state: CartState = { items: [] }
    const next = cartReducer(state, { type: 'setQuantity', productId: 9, quantity: 2 })
    expect(next.items).toHaveLength(0)
  })

  it('remove drops the matching line', () => {
    const state: CartState = {
      items: [
        { productId: 1, name: 'A', priceInCents: 100, quantity: 1, stock: 5, isFlashSale: false },
        { productId: 2, name: 'B', priceInCents: 200, quantity: 1, stock: 5, isFlashSale: false },
      ],
    }
    const next = cartReducer(state, { type: 'remove', productId: 1 })
    expect(next.items).toHaveLength(1)
    expect(next.items[0].productId).toBe(2)
  })

  it('clear empties the cart', () => {
    const state: CartState = {
      items: [{ productId: 1, name: 'A', priceInCents: 100, quantity: 2, stock: 5, isFlashSale: false }],
    }
    const next = cartReducer(state, { type: 'clear' })
    expect(next.items).toHaveLength(0)
  })

  it('reconcile caps lines to current stock and drops deleted/sold-out products', () => {
    const state: CartState = {
      items: [
        { productId: 1, name: 'A', priceInCents: 100, quantity: 5, stock: 10, isFlashSale: false },
        { productId: 2, name: 'B', priceInCents: 200, quantity: 3, stock: 10, isFlashSale: false }, // stock dropped to 1
        { productId: 3, name: 'C', priceInCents: 300, quantity: 1, stock: 10, isFlashSale: false }, // product deleted
      ],
    }
    const next = cartReducer(state, {
      type: 'reconcile',
      products: [
        product({ id: 1, stock: 10, name: 'A', priceInCents: 100 }),
        product({ id: 2, stock: 1, name: 'B', priceInCents: 200 }),
      ],
    })
    expect(next.items).toHaveLength(2)
    expect(next.items.find((i) => i.productId === 1)?.quantity).toBe(5) // unchanged
    expect(next.items.find((i) => i.productId === 2)?.quantity).toBe(1) // capped from 3 to 1
    expect(next.items.find((i) => i.productId === 3)).toBeUndefined() // deleted
  })

  it('cartItemCount sums all line quantities', () => {
    const state: CartState = {
      items: [
        { productId: 1, name: 'A', priceInCents: 100, quantity: 2, stock: 5, isFlashSale: false },
        { productId: 2, name: 'B', priceInCents: 200, quantity: 3, stock: 5, isFlashSale: false },
      ],
    }
    expect(cartItemCount(state)).toBe(5)
  })

  it('cartTotalCents sums price × quantity across lines', () => {
    const state: CartState = {
      items: [
        { productId: 1, name: 'A', priceInCents: 1000, quantity: 2, stock: 5, isFlashSale: false }, // 2000
        { productId: 2, name: 'B', priceInCents: 500, quantity: 3, stock: 5, isFlashSale: false }, // 1500
      ],
    }
    expect(cartTotalCents(state)).toBe(3500)
  })
})
