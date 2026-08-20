import request from './request'
import type { Order, Review, ReviewCreateRequest, ReviewUpdateRequest } from './types'

/** GET /orders/{orderId}/reviews — reviews for an order (the drawer's edit view). */
export function listOrderReviews(orderId: number): Promise<Review[]> {
  return request.get<Review[]>(`/orders/${orderId}/reviews`)
}

/** POST /orders/{orderId}/reviews — create/upsert a review for a line item. */
export function createReview(orderId: number, req: ReviewCreateRequest): Promise<Review> {
  return request.post<Review>(`/orders/${orderId}/reviews`, req)
}

/** PUT /reviews/{reviewId} — edit an existing review. */
export function updateReview(reviewId: number, req: ReviewUpdateRequest): Promise<Review> {
  return request.put<Review>(`/reviews/${reviewId}`, req)
}

/** GET /orders/unreviewed — the 未评价 tab: delivered/completed orders with unreviewed items. */
export function listUnreviewedOrders(): Promise<Order[]> {
  return request.get<Order[]>('/orders/unreviewed')
}

/** POST /reviews/images — upload one jpg/png ≤20MB; returns the servable URL. */
export function uploadReviewImage(file: File): Promise<string> {
  const form = new FormData()
  form.append('file', file)
  return request.post<string>('/reviews/images', form)
}
