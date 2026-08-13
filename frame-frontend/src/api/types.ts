// Shared API types mirroring the backend's Result envelope + demo entities.

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

export interface LoginResponse {
  token: string
  tokenType: string
  expiresIn: number
  userId: number
  username: string
}

export interface Product {
  id: number
  name: string
  price: number
  stock: number
  description?: string
  createTime?: string
  updateTime?: string
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
