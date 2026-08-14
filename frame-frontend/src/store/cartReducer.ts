import type { Product } from '@/api/types'

/**
 * A single line in the cart. Holds a snapshot of the product fields the cart UI
 * needs (name, price, stock) so it doesn't have to re-fetch when rendering.
 */
export interface CartItem {
  productId: number
  name: string
  priceInCents: number
  quantity: number
  /** Stock cap at the moment of add — {@code add} never lets quantity exceed it. */
  stock: number
}

export interface CartState {
  items: CartItem[]
}

export type CartAction =
  | { type: 'add'; product: Product; quantity?: number }
  | { type: 'remove'; productId: number }
  | { type: 'setQuantity'; productId: number; quantity: number }
  | { type: 'clear' }
  /** Bring cart stock snapshots in line with the latest product list (e.g. after a refresh). */
  | { type: 'reconcile'; products: Product[] }

const DEFAULT_STATE: CartState = { items: [] }

/**
 * Pure cart reducer (no React, no storage) — unit-testable in isolation.
 *
 * - {@code add} caps quantity at the product's current stock (no over-ordering).
 * - {@code setQuantity} clamps to [0, stock]; a 0 removes the line.
 * - {@code reconcile} updates stock snapshots and drops/caps lines whose stock
 *   dropped to zero or whose product was deleted.
 */
export function cartReducer(state: CartState, action: CartAction): CartState {
  switch (action.type) {
    case 'add': {
      const qty = action.quantity ?? 1
      const existing = state.items.find((i) => i.productId === action.product.id)
      if (existing) {
        const nextQty = Math.min(existing.quantity + qty, action.product.stock ?? 0)
        if (nextQty <= 0) {
          return { items: state.items.filter((i) => i.productId !== action.product.id) }
        }
        return {
          items: state.items.map((i) =>
            i.productId === action.product.id ? { ...i, quantity: nextQty, stock: action.product.stock ?? 0 } : i,
          ),
        }
      }
      const cappedQty = Math.min(qty, action.product.stock ?? 0)
      if (cappedQty <= 0) return state
      return {
        items: [
          ...state.items,
          {
            productId: action.product.id,
            name: action.product.name,
            priceInCents: action.product.priceInCents,
            quantity: cappedQty,
            stock: action.product.stock ?? 0,
          },
        ],
      }
    }
    case 'remove':
      return { items: state.items.filter((i) => i.productId !== action.productId) }
    case 'setQuantity': {
      const existing = state.items.find((i) => i.productId === action.productId)
      if (!existing) return state
      const nextQty = Math.min(Math.max(action.quantity, 0), existing.stock)
      if (nextQty <= 0) {
        return { items: state.items.filter((i) => i.productId !== action.productId) }
      }
      return {
        items: state.items.map((i) =>
          i.productId === action.productId ? { ...i, quantity: nextQty } : i,
        ),
      }
    }
    case 'reconcile': {
      const byId = new Map(action.products.map((p) => [p.id, p]))
      const next: CartItem[] = []
      for (const item of state.items) {
        const p = byId.get(item.productId)
        if (!p) continue // product deleted — drop the line
        const cappedQty = Math.min(item.quantity, p.stock ?? 0)
        if (cappedQty <= 0) continue // out of stock — drop the line
        next.push({ ...item, quantity: cappedQty, stock: p.stock ?? 0, name: p.name, priceInCents: p.priceInCents })
      }
      return { items: next }
    }
    case 'clear':
      return DEFAULT_STATE
    default:
      return state
  }
}

/** Total line-item count across all cart lines (sum of quantities). */
export function cartItemCount(state: CartState): number {
  return state.items.reduce((sum, i) => sum + i.quantity, 0)
}

/** Grand total in integer cents — pass to display as {@code (total/100).toFixed(2)}. */
export function cartTotalCents(state: CartState): number {
  return state.items.reduce((sum, i) => sum + i.priceInCents * i.quantity, 0)
}
