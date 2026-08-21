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

export interface UserProfile {
  id: number
  username: string
  phone: string | null
  nickname: string | null
  avatarUrl: string | null
  signature: string | null
  role: string
}

export interface RegisterRequest {
  username: string
  password: string
  phone: string
  code: string
  agreed: boolean
}

export interface SendCodeRequest {
  phone: string
  purpose: 'REGISTER' | 'FORGOT_PASSWORD' | 'REBIND_OLD' | 'REBIND_NEW' | 'LOCKOUT_RESET'
  captchaId: string
  captchaAnswer: string
}

export interface CaptchaResponse {
  captchaId: string
  svgImage: string
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

/**
 * Detail view from GET /products/{id}: Product fields plus sold + avgRating
 * aggregates (used on the product detail page header).
 */
export interface ProductDetail extends Product {
  sold: number
  avgRating: number
}

/** List item from GET /products: Product fields plus sold + avgRating. */
export interface ProductListItem extends Product {
  sold: number
  avgRating: number
}

export interface OrderItem {
  id: number
  orderId: number
  productId: number
  name: string
  priceInCents: number
  quantity: number
}

/** A product review (UC010). One per (order, product). Mirrors backend ReviewVO. */
export interface Review {
  id: number
  orderId: number
  productId: number
  productName?: string
  qualityStars: number
  logisticsStars: number
  serviceStars: number
  content?: string
  imageUrls: string[]
  isAnonymous: boolean
  editedAt?: string
  createTime?: string
  /** Derived: true if the review was edited after first submit. */
  edited: boolean
}

export interface ReviewCreateRequest {
  orderId: number
  productId: number
  qualityStars: number
  logisticsStars: number
  serviceStars: number
  content?: string
  imageUrls?: string[]
  isAnonymous?: boolean
}

export interface ReviewUpdateRequest {
  qualityStars?: number
  logisticsStars?: number
  serviceStars?: number
  content?: string
  imageUrls?: string[]
  isAnonymous?: boolean
}

/** UC012 coin balance + daily-claim status (mirrors backend CoinBalanceVO). */
export interface CoinBalance {
  balance: number
  claimedToday: boolean
  expiringSoon: number
}

/** UC012 a coin ledger row (mirrors backend CoinHistoryVO). */
export interface CoinHistoryRow {
  id: number
  amount: number
  source: string
  refOrderId?: number
  expiresAt?: string
  createTime: string
  balanceAfter: number
}

export type OrderStatus =
  | 'PENDING'
  | 'PAID'
  | 'CANCELLED'
  | 'FAILED'
  | 'SHIPPED'
  | 'DELIVERED'
  | 'COMPLETED'
  | 'REFUND_PENDING'
  | 'REFUNDED'

/** Refund lifecycle for an order (mirrors backend RefundStatus enum). */
export type RefundStatus = 'NONE' | 'PENDING' | 'APPROVED' | 'DENIED'

/** Payment channel selected at checkout (mock — no real gateway). */
export type PaymentMethod = 'WECHAT' | 'ALIPAY' | 'BANK_CARD'

export interface Order {
  id: number
  userId: number
  customerName: string
  customerEmail: string
  shippingAddress: string
  totalInCents: number
  /** Actual amount paid after coin discount (UC005/UC012). */
  paidInCents: number
  status: OrderStatus
  idempotencyKey: string
  /** Phone captured at checkout for SMS-gated flows (UC001/015). */
  customerPhone?: string
  /** Payment channel chosen by the user at checkout. */
  paymentMethod?: PaymentMethod
  /** Carrier tracking number, set when the admin ships the order. */
  trackingNumber?: string
  /** Timestamps for each lifecycle transition (ISO strings from the backend). */
  paidAt?: string
  shippedAt?: string
  deliveredAt?: string
  completedAt?: string
  /** Refund state — NONE unless a refund has been requested. */
  refundStatus?: RefundStatus
  /** User-supplied reason for requesting a refund. */
  refundReason?: string
  /** Line items for the order (returned by GET /orders/{id}/items). */
  items?: OrderItem[]
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
  /** Phone for SMS verification / notifications (required by backend UC001). */
  customerPhone: string
  /** Payment channel (required — drives the mock payment flow). */
  paymentMethod: PaymentMethod
  /** UC005: if true, deduct `coinAmount` coins at checkout. */
  useCoins?: boolean
  /** UC005: coins to spend (100 = ¥1). Validated/clamped server-side. */
  coinAmount?: number
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
  /** Search keyword (split on comma/space into ANDed LIKE terms server-side). */
  keyword?: string
  /** Exact category filter. */
  category?: string
  /** Sort field: 'price' | 'sales' | 'rating'. Omit = by recency. */
  sortField?: 'price' | 'sales' | 'rating'
  /** Sort direction. */
  sortOrder?: 'asc' | 'desc'
}
