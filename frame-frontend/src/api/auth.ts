import request from './request'
import type { LoginRequest, LoginResponse } from './types'

/** POST /auth/login — returns the JWT. Public endpoint (no token needed). */
export function login(payload: LoginRequest): Promise<LoginResponse> {
  return request.post<LoginResponse>('/auth/login', payload)
}

/**
 * POST /auth/register — creates a new user (role=USER). Public endpoint.
 * Returns the created user (no token — the caller logs in afterwards).
 */
export function register(payload: LoginRequest): Promise<RegisterResult> {
  return request.post<RegisterResult>('/auth/register', payload)
}

/** GET /auth/me — echoes the current user from the JWT. Requires auth. */
export function fetchMe(): Promise<unknown> {
  return request.get('/auth/me')
}

/** Shape returned by /auth/register (the created User entity). */
export interface RegisterResult {
  id: number
  username: string
  role: string
}
