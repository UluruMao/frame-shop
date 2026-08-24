import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'

// Mock at the @/api/chat + @/hooks/useChat boundaries (Phase B final-review
// smell): the page's wiring (openSession on mount, fetchHistory on session,
// submit -> send) is exercised against controllable stand-ins.
vi.mock('@/api/chat', () => ({
  openSession: vi.fn().mockResolvedValue({ id: 7, userId: 1, adminId: null, status: 'OPEN', createTime: '', updateTime: '' }),
  fetchHistory: vi.fn().mockResolvedValue({ records: [], total: 0 }),
}))
const mockSend = vi.fn()
vi.mock('@/hooks/useChat', () => ({
  useChat: () => ({ messages: [], send: mockSend, connected: true, setMessages: vi.fn() }),
}))

import Chat from '@/pages/Chat'

describe('Chat page', () => {
  it('renders heading, input, and send', async () => {
    render(<Chat />)
    expect(await screen.findByText('联系客服')).toBeInTheDocument()
    const input = screen.getByPlaceholderText('输入消息…')
    fireEvent.change(input, { target: { value: 'hi' } })
    fireEvent.click(screen.getByText('发送'))
    expect(mockSend).toHaveBeenCalledWith('hi', null)
  })
})
