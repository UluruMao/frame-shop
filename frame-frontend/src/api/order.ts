import request from './request'
import type {
  CheckoutRequest,
  CheckoutResponse,
  Order,
  OrderItem,
  OrderStatus,
  PageResult,
} from './types'

/**
 * POST /checkout — the flash-sale hot path. Returns 202 with a correlation
 * {@code orderId} and {@code status: "queued"}. The client then polls
 * {@link getOrder} until the consumer resolves it.
 */
export function checkout(payload: CheckoutRequest): Promise<CheckoutResponse> {
  return request.post<CheckoutResponse>('/checkout', payload)
}

/**
 * GET /orders/{id} — resolve an order by its correlation id (from the 202) or
 * DB PK. While the consumer hasn't persisted yet, returns a synthetic
 * placeholder Order (the client polls until the real row appears).
 */
export function getOrder(id: number): Promise<Order> {
  return request.get<Order>(`/orders/${id}`)
}

/**
 * GET /orders/me — paginated list of the current user's orders. The optional
 * {@code status} filters server-side (e.g. `/orders/me?status=PAID`); omitting
 * it returns orders across all statuses.
 */
export function getMyOrders(
  current = 1,
  size = 20,
  status?: OrderStatus,
): Promise<PageResult<Order>> {
  return request.get<PageResult<Order>>('/orders/me', {
    params: { current, size, ...(status ? { status } : {}) },
  })
}

/** POST /orders/{id}/pay — mock payment; transitions PENDING → PAID. */
export function payOrder(id: number): Promise<Order> {
  return request.post<Order>(`/orders/${id}/pay`)
}

/** POST /orders/{id}/cancel — transitions PENDING → CANCELLED. */
export function cancelOrder(id: number): Promise<void> {
  return request.post(`/orders/${id}/cancel`)
}

// --- User lifecycle actions (Phase C) -------------------------------------

/** POST /orders/{id}/cancel-paid — cancels a PAID order before shipment. */
export function cancelPaid(id: number): Promise<Order> {
  return request.post<Order>(`/orders/${id}/cancel-paid`)
}

/** POST /orders/{id}/refund-request — requests a refund with a reason. */
export function requestRefund(id: number, reason: string): Promise<Order> {
  return request.post<Order>(`/orders/${id}/refund-request`, { reason })
}

/** POST /orders/{id}/confirm-receipt — user confirms delivery (→ COMPLETED). */
export function confirmReceipt(id: number): Promise<Order> {
  return request.post<Order>(`/orders/${id}/confirm-receipt`)
}

/** GET /orders/{id}/items — line items for a resolved order. */
export function getOrderItems(id: number): Promise<OrderItem[]> {
  return request.get<OrderItem[]>(`/orders/${id}/items`)
}

// --- Admin actions (Phase C) ----------------------------------------------

/** GET /admin/orders — paginated list of all orders, optionally by status. */
export function adminListOrders(
  status?: OrderStatus,
  current = 1,
  size = 20,
): Promise<PageResult<Order>> {
  return request.get<PageResult<Order>>('/admin/orders', {
    params: { current, size, ...(status ? { status } : {}) },
  })
}

/** POST /admin/orders/{id}/ship — marks PAID → SHIPPED with a tracking number. */
export function adminShip(id: number, trackingNumber: string): Promise<Order> {
  return request.post<Order>(`/admin/orders/${id}/ship`, { trackingNumber })
}

/** POST /admin/orders/{id}/deliver — marks SHIPPED → DELIVERED. */
export function adminDeliver(id: number): Promise<Order> {
  return request.post<Order>(`/admin/orders/${id}/deliver`)
}

/**
 * GET /admin/refunds — paginated list of all orders with a pending refund
 * request. The backend returns `Result<PageResult<Order>>` (the axios instance
 * unwraps to the page), so consume `.records` for the rows.
 */
export function adminRefundRequests(
  current = 1,
  size = 100,
): Promise<PageResult<Order>> {
  return request.get<PageResult<Order>>('/admin/refunds', {
    params: { current, size },
  })
}

/** POST /admin/refunds/{orderId}/approve — approve the refund (→ REFUNDED). */
export function adminApproveRefund(orderId: number): Promise<Order> {
  return request.post<Order>(`/admin/refunds/${orderId}/approve`)
}

/** POST /admin/refunds/{orderId}/deny — deny the refund (→ back to prior status). */
export function adminDenyRefund(orderId: number): Promise<Order> {
  return request.post<Order>(`/admin/refunds/${orderId}/deny`)
}
