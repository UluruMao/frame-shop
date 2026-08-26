import request from './request'
import type { PageQuery, PageResult, Product, ProductDetail, ProductListItem } from './types'

export function pageProducts(query: PageQuery = {}): Promise<PageResult<ProductListItem>> {
  return request.get<PageResult<ProductListItem>>('/products', {
    params: {
      current: query.current ?? 1,
      size: query.size ?? 12,
      keyword: query.keyword,
      category: query.category,
      sortField: query.sortField,
      sortOrder: query.sortOrder,
    },
  })
}

export function getProduct(id: number): Promise<Product> {
  return request.get<Product>(`/products/${id}`)
}

export function getProductDetail(id: number): Promise<ProductDetail> {
  return request.get<ProductDetail>(`/products/${id}`)
}

export function listCategories(): Promise<string[]> {
  return request.get<string[]>('/products/categories')
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

/** POST /admin/sales/{id} — load product stock into Redis to open a flash sale. Admin-only. */
export function openFlashSale(id: number): Promise<void> {
  return request.post(`/admin/sales/${id}`)
}
