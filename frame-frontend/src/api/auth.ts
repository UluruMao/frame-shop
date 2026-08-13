import request from './request'
import type { LoginRequest, LoginResponse } from './types'

/** POST /auth/login — returns the JWT. Public endpoint (no token needed). */
export function login(payload: LoginRequest): Promise<LoginResponse> {
  return request.post<LoginResponse>('/auth/login', payload)
}

/** GET /auth/me — echoes the current user from the JWT. Requires auth. */
export function fetchMe(): Promise<unknown> {
  return request.get('/auth/me')
}
