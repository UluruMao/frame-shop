import { renderHook, act } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'

// Mock the stompjs Client at the library boundary (per the Phase B final-review
// smell: mock at the library/hook boundary, NOT at axios). This lets the real
// useChat wiring — Client construction, connectHeaders, subscribe destination,
// publish destination — be exercised against a controllable stand-in.
const mockPublish = vi.fn()
const mockActivate = vi.fn()
let onConnectCallback: (() => void) | null = null
let onMessageCallback: ((frame: any) => void) | null = null

vi.mock('@stomp/stompjs', () => ({
  Client: vi.fn().mockImplementation((config: any) => {
    onConnectCallback = config.onConnect
    return {
      activate: mockActivate,
      publish: mockPublish,
      subscribe: (_dest: string, cb: (frame: any) => void) => {
        onMessageCallback = cb
        return { unsubscribe: vi.fn() }
      },
      deactivate: vi.fn(),
    }
  }),
}))
vi.mock('sockjs-client', () => ({ default: vi.fn() }))

import { useChat } from '@/hooks/useChat'
import { useAuthStore } from '@/store/useAuthStore'

describe('useChat', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    onConnectCallback = null
    onMessageCallback = null
    // Zustand persist hydrates once at module load, so writing to localStorage
    // in beforeEach is too late — set the token on the in-memory store directly.
    useAuthStore.getState().setAuth('fake.jwt', { userId: 1001, username: 'tester', role: 'USER' })
  })

  it('connects with the token and surfaces received messages', async () => {
    const { result } = renderHook(() => useChat(7))
    expect(mockActivate).toHaveBeenCalled()
    // simulate connect
    act(() => onConnectCallback?.())
    expect(result.current.connected).toBe(true)
    // simulate an inbound message
    act(() => onMessageCallback?.({ body: JSON.stringify({ id: 1, sessionId: 7, senderType: 'USER', content: 'hi', senderId: 1001 }) }))
    expect(result.current.messages).toHaveLength(1)
    expect(result.current.messages[0].content).toBe('hi')
  })

  it('send publishes to /app/chat/{sessionId}', async () => {
    const { result } = renderHook(() => useChat(7))
    act(() => onConnectCallback?.())
    await act(async () => { await result.current.send('hello', null) })
    expect(mockPublish).toHaveBeenCalled()
    const call = mockPublish.mock.calls[0][0]
    expect(call.destination).toBe('/app/chat/7')
  })
})
