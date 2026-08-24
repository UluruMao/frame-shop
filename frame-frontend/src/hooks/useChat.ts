import { useEffect, useRef, useState } from 'react'
import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { useAuthStore } from '@/store/useAuthStore'
import type { ChatMessageVO } from '@/api/chat'

/**
 * Manages the @stomp/stompjs Client lifecycle for a chat session (UC011).
 *
 * - Connects via SockJS to the /ws-chat endpoint (the prod endpoint is SockJS).
 * - Carries the JWT in the CONNECT frame's `Authorization` header (matches
 *   AuthChannelInterceptor, which reads the native Authorization header).
 * - Subscribes to /topic/session/{sessionId} to receive broadcast messages.
 * - Sends to /app/chat/{sessionId} (the @MessageMapping destination).
 * - reconnectDelay is the stompjs default (auto-reconnect).
 *
 * Mocked at the stompjs Client boundary in tests (per the Phase B final-review
 * smell: mock the library, not axios).
 */
export function useChat(sessionId: number | null) {
  const [messages, setMessages] = useState<ChatMessageVO[]>([])
  const [connected, setConnected] = useState(false)
  const clientRef = useRef<Client | null>(null)

  useEffect(() => {
    if (!sessionId) return
    const token = useAuthStore.getState().token
    if (!token) return
    const client = new Client({
      webSocketFactory: () => new SockJS('/ws-chat') as any,
      connectHeaders: { Authorization: `Bearer ${token}` },
      onConnect: () => {
        setConnected(true)
        client.subscribe(`/topic/session/${sessionId}`, (frame) => {
          const msg = JSON.parse(frame.body) as ChatMessageVO
          setMessages((prev) => [...prev, msg])
        })
      },
      onDisconnect: () => setConnected(false),
    })
    clientRef.current = client
    client.activate()
    return () => { client.deactivate() }
  }, [sessionId])

  const send = (content: string, imageUrl: string | null) => {
    const client = clientRef.current
    if (!client || !connected) return
    client.publish({
      destination: `/app/chat/${sessionId}`,
      body: JSON.stringify({ content, imageUrl }),
    })
  }

  return { messages, send, connected, setMessages }
}
