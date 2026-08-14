import request from './request'
import type { PageQuery, PageResult, Product } from './types'

export function pageProducts(query: PageQuery = {}): Promise<PageResult<Product>> {
  return request.get<PageResult<Product>>('/products', {
    params: { current: query.current ?? 1, size: query.size ?? 10 },
  })
}

export function getProduct(id: number): Promise<Product> {
  return request.get<Product>(`/products/${id}`)
}

export function createProduct(payload: Omit<Product, 'id'>): Promise<Product> {
  return request.post<Product>('/products', payload)
}

export function updateProduct(id: number, payload: Omit<Product, 'id'>): Promise<Product> {
  return request.put<Product>(`/products/${id}`, payload)
}

export function deleteProduct(id: number): Promise<void> {
  return request.delete(`/products/${id}`)
}

/** POST /products/{id}/image — multipart upload; returns the image URL. Admin-only. */
export function uploadImage(id: number, file: File): Promise<string> {
  const form = new FormData()
  form.append('file', file)
  return request.post<string>(`/products/${id}/image`, form)
}

/** POST /admin/sales/{id} — load product stock into Redis to open a flash sale. Admin-only. */
export function openFlashSale(id: number): Promise<void> {
  return request.post(`/admin/sales/${id}`)
}
