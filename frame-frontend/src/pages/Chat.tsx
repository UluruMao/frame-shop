import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { openSession, fetchHistory, deleteSession, type ChatSessionVO } from '@/api/chat'
import { useChat } from '@/hooks/useChat'
import Button from '@/components/ui/Button'
import Input from '@/components/ui/Input'
import PageHeader from '@/components/ui/PageHeader'

/**
 * User chat page (UC011). On mount, opens (or reuses) a chat session via
 * openSession(), loads history, then useChat(sessionId) manages the STOMP
 * connection for live messages. Renders the message list + an input + send.
 * A "删除聊天" button soft-deletes the session (syncs the admin inbox via the
 * backend); after delete the page shows an empty state until reload.
 */
export default function Chat() {
  const [session, setSession] = useState<ChatSessionVO | null>(null)
  const [deleted, setDeleted] = useState(false)
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

  async function handleDelete() {
    if (!session) return
    if (!confirm('删除此聊天？管理员端也将不再显示。')) return
    try {
      await deleteSession(session.id)
      setSession(null)
      setMessages([])
      setDeleted(true)
    } catch {
      // delete failed — keep current session visible so the user can retry
    }
  }

  return (
    <div className="mx-auto max-w-2xl p-4">
      <div className="mb-4 flex items-center justify-between gap-2">
        <PageHeader title="联系客服" />
        {session !== null && (
          <Button variant="secondary" onClick={() => void handleDelete()}>删除聊天</Button>
        )}
      </div>
      {deleted ? (
        <div className="rounded border border-slate-200 bg-slate-50 p-8 text-center text-slate-400">
          聊天已删除
        </div>
      ) : session === null ? (
        <div className="h-96" />
      ) : (
        <>
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
        </>
      )}
    </div>
  )
}
