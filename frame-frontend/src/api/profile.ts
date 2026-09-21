import request from './request'

export function updateProfile(nickname: string, signature: string): Promise<void> {
  return request.put('/auth/profile', { nickname, signature })
}

export function uploadAvatar(file: File): Promise<string> {
  const form = new FormData()
  form.append('file', file)
  // Let axios/browser set multipart/form-data with a boundary. The shared
  // request instance defaults Content-Type to application/json, which makes
  // axios stringify FormData to JSON and triggers a backend MultipartException (500).
  return request.post<string>('/auth/avatar', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

export function rebindInit(oldPhoneUsable: boolean): Promise<void> {
  return request.post('/auth/phone/rebind/init', { oldPhoneUsable })
}
export function rebindVerifyOld(code: string | null, password: string | null): Promise<void> {
  return request.post('/auth/phone/rebind/verify-old', { code, password })
}
export function rebindConfirm(newPhone: string, code: string): Promise<void> {
  return request.post('/auth/phone/rebind/confirm', { newPhone, code })
}
