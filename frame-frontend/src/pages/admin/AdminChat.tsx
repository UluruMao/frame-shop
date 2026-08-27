import { useEffect, useState, type FormEvent } from 'react'
import { Shield, MessageCircle } from 'lucide-react'
import { adminInbox, adminHistory, type ChatSessionVO, type ChatMessageVO } from '@/api/chat'
import { useChat } from '@/hooks/useChat'
import { useIsAdmin } from '@/store/useAuthStore'
import Button from '@/components/ui/Button'
import Input from '@/components/ui/Input'
import Badge from '@/components/ui/Badge'
import Table from '@/components/ui/Table'

/**
 * Admin chat inbox: lists open sessions, lets the admin open one and reply.
 * RequireAdmin guards the route tree; the local useIsAdmin gate is
 * defense-in-depth for direct renders.
 */
export default function AdminChat() {
  const isAdmin = useIsAdmin()
  const [inbox, setInbox] = useState<ChatSessionVO[]>([])
  const [inboxLoading, setInboxLoading] = useState(false)
  const [inboxError, setInboxError] = useState<string | null>(null)
  const [selectedSessionId, setSelectedSessionId] = useState<number | null>(null)

  async function loadInbox() {
    setInboxLoading(true)
    setInboxError(null)
    try {
      const sessions = await adminInbox()
      setInbox(sessions)
    } catch (err) {
      setInboxError(err instanceof Error ? err.message : '加载会话失败')
    } finally {
      setInboxLoading(false)
    }
  }

  // Load the open sessions on mount.
  useEffect(() => {
    if (isAdmin) void loadInbox()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAdmin])

  if (!isAdmin) {
    return (
      <div className="flex flex-col items-center gap-3 py-16 text-center">
        <Shield className="w-10 h-10 text-slate-300" />
        <h1 className="text-lg font-semibold text-slate-700">需要管理员权限</h1>
        <p className="text-sm text-slate-500">
          您没有权限查看此页面。
        </p>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <MessageCircle className="w-5 h-5 text-brand-600" />
        <h1 className="text-xl font-semibold text-slate-800">客服会话</h1>
        <span className="text-sm text-slate-400">({inbox.length} 个开放会话)</span>
      </div>

      <section className="space-y-3">
        <div className="flex items-center gap-2">
          <MessageCircle className="w-4 h-4 text-brand-600" />
          <h2 className="text-sm font-medium text-slate-700">开放会话</h2>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => { void loadInbox() }}
            className="ml-auto"
          >
            刷新
          </Button>
        </div>
        {inboxError && <p className="text-sm text-red-600">{inboxError}</p>}
        {inboxLoading ? (
          <p className="text-sm text-slate-500">加载中…</p>
        ) : inbox.length === 0 ? (
          <p className="text-sm text-slate-400">暂无开放会话。</p>
        ) : (
          <Table<ChatSessionVO>
            rowKey={(s) => String(s.id)}
            data={inbox}
            empty="暂无开放会话。"
            columns={[
              { key: 'id', header: '会话', render: (s) => <span className="text-slate-500">#{s.id}</span> },
              { key: 'userId', header: '用户', render: (s) => <span className="text-slate-800">用户 #{s.userId}</span> },
              { key: 'status', header: '状态', render: (s) => <Badge color="green">{s.status}</Badge> },
              {
                key: 'createTime',
                header: '创建时间',
                render: (s) => (
                  <span className="text-xs text-slate-600">
                    {s.createTime
                      ? new Date(s.createTime).toLocaleString(undefined, {
                          month: 'short',
                          day: 'numeric',
                          hour: '2-digit',
                          minute: '2-digit',
                        })
                      : '—'}
                  </span>
                ),
              },
              {
                key: 'actions',
                header: '操作',
                className: 'text-right',
                render: (s) => (
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => setSelectedSessionId(s.id)}
                  >
                    {selectedSessionId === s.id ? '已选' : '打开'}
                  </Button>
                ),
              },
            ]}
          />
        )}
      </section>

      {/* Reply panel for the selected session. Rendered as a separate
          component so useChat(sessionId) is called unconditionally inside
          it (Rules of Hooks) — the parent gates rendering, not the hook. */}
      {selectedSessionId !== null && (
        <ChatReplyPanel sessionId={selectedSessionId} />
      )}
    </div>
  )
}

/**
 * Admin reply panel for a single chat session. Loads history via adminHistory,
 * then useChat(sessionId) subscribes to the live topic so the admin sees new
 * messages. Reply input sends to /app/chat/{sessionId} (same destination as the
 * user; the backend tags admin messages with senderType=ADMIN).
 */
function ChatReplyPanel({ sessionId }: { sessionId: number }) {
  const { messages, send, connected, setMessages } = useChat(sessionId)
  const [input, setInput] = useState('')
  const [historyLoading, setHistoryLoading] = useState(true)

  useEffect(() => {
    setHistoryLoading(true)
    adminHistory(sessionId)
      .then((r) => setMessages(r.records))
      .catch(() => {})
      .finally(() => setHistoryLoading(false))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId])

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (!input.trim()) return
    send(input.trim(), null)
    setInput('')
  }

  return (
    <section className="space-y-3">
      <div className="flex items-center gap-2">
        <h2 className="text-sm font-medium text-slate-700">会话 #{sessionId}</h2>
        <span className="text-xs text-slate-400">{connected ? '已连接' : '连接中…'}</span>
      </div>
      <div className="h-80 overflow-y-auto rounded border border-slate-200 p-3 bg-slate-50">
        {historyLoading && <p className="text-sm text-slate-500">加载历史记录…</p>}
        {messages.map((m: ChatMessageVO) => (
          <div key={m.id} className={`mb-2 ${m.senderType === 'ADMIN' ? 'text-right' : 'text-left'}`}>
            <span
              className={`inline-block rounded px-3 py-1 ${
                m.senderType === 'ADMIN' ? 'bg-brand-600 text-white' : 'bg-slate-200 text-slate-800'
              }`}
            >
              {m.content}
            </span>
          </div>
        ))}
        {!historyLoading && messages.length === 0 && (
          <p className="text-sm text-slate-400 text-center">暂无消息。</p>
        )}
      </div>
      <form onSubmit={submit} className="flex gap-2">
        <Input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="回复消息…"
        />
        <Button
          type="submit"
          disabled={!connected}
        >
          发送
        </Button>
      </form>
    </section>
  )
}
