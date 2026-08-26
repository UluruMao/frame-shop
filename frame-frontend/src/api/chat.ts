import request from './request'

export interface ChatMessageVO {
  id: number
  sessionId: number
  senderType: 'USER' | 'ADMIN'
  senderId: number
  content: string
  imageUrl: string | null
  createTime: string
}
export interface ChatSessionVO {
  id: number
  userId: number
  adminId: number | null
  status: 'OPEN' | 'CLOSED'
  createTime: string
  updateTime: string
}

export async function openSession(): Promise<ChatSessionVO> {
  return request.post<ChatSessionVO>('/chat/sessions')
}
export async function fetchHistory(sessionId: number, current = 1, size = 50): Promise<{ records: ChatMessageVO[]; total: number }> {
  return request.get(`/chat/sessions/${sessionId}/messages`, { params: { current, size } })
}
export async function adminInbox(): Promise<ChatSessionVO[]> {
  return request.get<ChatSessionVO[]>('/admin/chat/sessions')
}
export async function adminHistory(sessionId: number, current = 1, size = 50): Promise<{ records: ChatMessageVO[]; total: number }> {
  return request.get(`/admin/chat/sessions/${sessionId}/messages`, { params: { current, size } })
}
