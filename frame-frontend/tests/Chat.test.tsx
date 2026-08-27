import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'

// Mock at the @/api/chat + @/hooks/useChat boundaries (Phase B final-review
// smell): the page's wiring (openSession on mount, fetchHistory on session,
// submit -> send) is exercised against controllable stand-ins.
vi.mock('@/api/chat', () => ({
  openSession: vi.fn().mockResolvedValue({ id: 7, userId: 1, adminId: null, status: 'OPEN', createTime: '', updateTime: '' }),
  fetchHistory: vi.fn().mockResolvedValue({ records: [], total: 0 }),
  deleteSession: vi.fn().mockResolvedValue(undefined),
}))
const mockSend = vi.fn()
const mockSetMessages = vi.fn()
vi.mock('@/hooks/useChat', () => ({
  useChat: () => ({ messages: [], send: mockSend, connected: true, setMessages: mockSetMessages }),
}))

import { deleteSession } from '@/api/chat'
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

  it('renders Chinese labels (regression guard)', async () => {
    render(<Chat />)
    expect(await screen.findByText('联系客服')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('输入消息…')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '发送' })).toBeInTheDocument()
  })

  it('deletes the chat session on click', async () => {
    // jsdom's window.confirm returns false in this setup, which would abort
    // handleDelete before deleteSession runs. Force-confirm per the brief.
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<Chat />)
    await screen.findByText('联系客服')
    fireEvent.click(screen.getByRole('button', { name: '删除聊天' }))
    // confirm() is a window.confirm stub — jsdom returns true by default; if it
    // returns false, mock vi.spyOn(window, 'confirm').mockReturnValue(true) first.
    await waitFor(() => expect(deleteSession).toHaveBeenCalledWith(7))
  })
})
