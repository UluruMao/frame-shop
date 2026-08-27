import request from './request'
import type { LoginRequest, LoginResponse, RegisterRequest, UserProfile, SendCodeRequest, CaptchaResponse } from './types'

/** POST /auth/login — returns the JWT. Public endpoint (no token needed). */
export function login(payload: LoginRequest): Promise<LoginResponse> {
  return request.post<LoginResponse>('/auth/login', payload)
}

export function register(payload: RegisterRequest): Promise<{ id: number; username: string; role: string }> {
  return request.post('/auth/register', payload)
}

export function fetchMe(): Promise<UserProfile> {
  return request.get<UserProfile>('/auth/me')
}

export function getCaptcha(): Promise<CaptchaResponse> {
  return request.get<CaptchaResponse>('/captcha')
}

export function sendCode(payload: SendCodeRequest): Promise<void> {
  return request.post('/auth/sms/send', payload)
}

export function forgotPassword(phone: string, code: string, newPassword: string): Promise<void> {
  return request.post('/auth/password/forgot', { phone, code, newPassword })
}

export function changePassword(oldPassword: string, newPassword: string): Promise<void> {
  return request.post('/auth/password/change', { oldPassword, newPassword })
}

export function deleteAccount(password: string): Promise<void> {
  return request.post('/auth/account/delete', { password })
}
