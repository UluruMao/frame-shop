import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { openSession, fetchHistory, type ChatSessionVO } from '@/api/chat'
import { useChat } from '@/hooks/useChat'
import Button from '@/components/ui/Button'
import Input from '@/components/ui/Input'
import PageHeader from '@/components/ui/PageHeader'

/**
 * User chat page (UC011). On mount, opens (or reuses) a chat session via
 * openSession(), loads history, then useChat(sessionId) manages the STOMP
 * connection for live messages. Renders the message list + an input + send.
 */
export default function Chat() {
  const [session, setSession] = useState<ChatSessionVO | null>(null)
  const { messages, send, connected, setMessages } = useChat(session?.id ?? null)
  const [input, setInput] = useState('')

  useEffect(() => {
    openSession().then(setSession).catch(() => {})
  }, [])
  useEffect(() => {
    if (session) fetchHistory(session.id).then((r) => setMessages(r.records)).catch(() => {})
  }, [session, setMessages])

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (!input.trim()) return
    send(input.trim(), null)
    setInput('')
  }

  return (
    <div className="mx-auto max-w-2xl p-4">
      <PageHeader title="联系客服" className="mb-4" />
      <div className="mb-2 text-sm text-slate-500">{connected ? '已连接' : '连接中…'}</div>
      <div className="h-96 overflow-y-auto rounded border p-3 mb-3 bg-slate-50">
        {messages.map((m) => (
          <div key={m.id} className={`mb-2 ${m.senderType === 'USER' ? 'text-right' : 'text-left'}`}>
            <span className={`inline-block rounded px-3 py-1 ${m.senderType === 'USER' ? 'bg-brand-600 text-white' : 'bg-slate-200'}`}>
              {m.content}
            </span>
          </div>
        ))}
      </div>
      <form onSubmit={submit} className="flex gap-2">
        <Input value={input} onChange={(e) => setInput(e.target.value)} className="flex-1" placeholder="输入消息…" />
        <Button type="submit" disabled={!connected}>发送</Button>
      </form>
    </div>
  )
}
