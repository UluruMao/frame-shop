import request from './request'
import type { CheckoutRequest, CheckoutResponse, Order, PageResult } from './types'

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

/** GET /orders/me — paginated list of the current user's orders. */
export function getMyOrders(current = 1, size = 20): Promise<PageResult<Order>> {
  return request.get<PageResult<Order>>('/orders/me', { params: { current, size } })
}

/** POST /orders/{id}/pay — mock payment; transitions PENDING → PAID. */
export function payOrder(id: number): Promise<Order> {
  return request.post<Order>(`/orders/${id}/pay`)
}

/** POST /orders/{id}/cancel — transitions PENDING → CANCELLED. */
export function cancelOrder(id: number): Promise<void> {
  return request.post(`/orders/${id}/cancel`)
}
