import { createContext, useContext, useEffect, useMemo, useReducer, type ReactNode } from 'react'
import { cartReducer, cartItemCount, cartTotalCents, type CartItem, type CartState } from './cartReducer'
import type { Product } from '@/api/types'

const STORAGE_KEY = 'frame-cart'

interface CartContextValue {
  items: CartItem[]
  count: number
  totalCents: number
  add: (product: Product, quantity?: number) => void
  remove: (productId: number) => void
  setQuantity: (productId: number, quantity: number) => void
  reconcile: (products: Product[]) => void
  clear: () => void
}

const CartContext = createContext<CartContextValue | null>(null)

function loadInitial(): CartState {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return { items: [] }
    const parsed = JSON.parse(raw) as CartState
    if (!parsed || !Array.isArray(parsed.items)) return { items: [] }
    return parsed
  } catch {
    return { items: [] }
  }
}

/**
 * Cart store: a pure reducer ({@link cartReducer}) wrapped in a React context
 * with localStorage persistence. Consumed by the Products, Checkout, and
 * Checkout-page-nav surfaces.
 */
export function CartProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(cartReducer, undefined, loadInitial)

  // Persist on every change.
  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
    } catch {
      // quota / private mode — cart stays in-memory only
    }
  }, [state])

  const value = useMemo<CartContextValue>(
    () => ({
      items: state.items,
      count: cartItemCount(state),
      totalCents: cartTotalCents(state),
      add: (product, quantity) => dispatch({ type: 'add', product, quantity }),
      remove: (productId) => dispatch({ type: 'remove', productId }),
      setQuantity: (productId, quantity) => dispatch({ type: 'setQuantity', productId, quantity }),
      reconcile: (products) => dispatch({ type: 'reconcile', products }),
      clear: () => dispatch({ type: 'clear' }),
    }),
    [state],
  )

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>
}

export function useCart(): CartContextValue {
  const ctx = useContext(CartContext)
  if (!ctx) throw new Error('useCart must be used inside a <CartProvider>')
  return ctx
}
