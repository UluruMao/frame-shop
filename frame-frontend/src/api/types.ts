// Shared API types mirroring the backend's Result envelope + domain entities.

/** Backend unified response: { code, message, data, timestamp }. */
export interface Result<T> {
  code: number
  message: string
  data: T
  timestamp: number
}

export interface LoginRequest {
  username: string
  password: string
}

/**
 * Backend {@code LoginResponse}. The {@code role} field ("USER"|"ADMIN") is
 * present on the backend DTO; admins see the Admin page + product mutations.
 */
export interface LoginResponse {
  token: string
  tokenType: string
  expiresIn: number
  userId: number
  username: string
  role: string
}

export interface Product {
  id: number
  name: string
  /** Unit price in integer cents (backend source of truth — display as dollars). */
  priceInCents: number
  stock: number
  imageUrl?: string
  category?: string
  isFlashSale?: boolean
  flashSaleAt?: string
  description?: string
  createTime?: string
  updateTime?: string
}

export interface OrderItem {
  id: number
  orderId: number
  productId: number
  name: string
  priceInCents: number
  quantity: number
}

export type OrderStatus = 'PENDING' | 'PAID' | 'CANCELLED' | 'FAILED'

export interface Order {
  id: number
  userId: number
  customerName: string
  customerEmail: string
  shippingAddress: string
  totalInCents: number
  status: OrderStatus
  idempotencyKey: string
  createTime?: string
  updateTime?: string
}

export interface CheckoutItem {
  productId: number
  quantity: number
}

export interface CheckoutRequest {
  items: CheckoutItem[]
  customerName: string
  customerEmail: string
  shippingAddress: string
  idempotencyKey: string
}

/**
 * Backend returns a 202 with a correlation {@code orderId} (NOT the DB PK) and
 * {@code status: "queued"}. The client polls {@code GET /orders/{orderId}} until
 * the consumer transitions the order to PENDING (pay) or FAILED (retry).
 */
export interface CheckoutResponse {
  orderId: number
  status: 'queued' | 'pending'
}

export interface PageResult<T> {
  total: number
  current: number
  size: number
  records: T[]
}

export interface PageQuery {
  current?: number
  size?: number
}
