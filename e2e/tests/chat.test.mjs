import { http } from '../lib/http.mjs'
import { assert } from '../lib/assert.mjs'
import { env } from '../lib/env.mjs'
import { registerWithCode } from '../lib/sms.mjs'

// UC011 — live chat REST surface (Phase F).
//
// The real-time STOMP/SockJS path is proven by the backend ChatIntegrationTest
// (Task 7); the fetch-based e2e runner can't speak STOMP, so this suite covers
// only the REST contract that the STOMP layer also depends on:
//   - POST /chat/sessions                 (USER) → Result<ChatSessionVO>
//     ChatSessionVO = {id, userId, adminId, status:'OPEN'|'CLOSED', createTime, updateTime}
//   - GET  /chat/sessions/{id}/messages   (USER, owner-only) → Result<PageResult<ChatMessageVO>>
//     PageResult = {total, current, size, records:[...]}
//     Ownership is enforced in ChatServiceImpl.loadAndCheckOwnership: a non-owner
//     reading an existing session → BusinessException(FORBIDDEN) → HTTP 403.
//   - GET  /admin/chat/sessions           (ADMIN) → Result<List<ChatSessionVO>>
//   - GET  /admin/chat/sessions/{id}/messages (ADMIN) → Result<PageResult>
//
// User tests register real users via registerWithCode (Task 1's SMS-cascade-safe
// helper), NOT the admin-as-user shortcut other suites use — the chat ownership
// check keys on the authenticated userId, so user A vs user B must be distinct
// real users for the 403 assertion to be meaningful. Admin logs in via env.ADMIN
// like the other suites.

export const suite = {
  name: 'chat',
  tests: [
    {
      name: 'setup: admin login → ctx.adminToken',
      run: async (ctx) => {
        if (!ctx.adminToken) {
          const a = await http.post('/auth/login', env.ADMIN)
          assert(a.status === 200, `admin login expected 200, got ${a.status}`)
          ctx.adminToken = a.body.data.token
        }
      },
    },
    {
      name: 'UC011 user opens a chat session → 200 + id',
      run: async (ctx) => {
        const reg = await registerWithCode(http, {
          username: 'chat' + Date.now(),
          password: 'Pass1234!',
        })
        assert(reg.status === 200, `register expected 200, got ${reg.status}`)
        const token = reg.body.data.token
        const res = await http.post('/chat/sessions', {}, { token })
        assert(res.status === 200, `expected 200 opening session, got ${res.status}`)
        assert(res.body.data && res.body.data.id, 'expected session id present')
        assert(res.body.data.status === 'OPEN',
          `expected status OPEN, got ${res.body.data.status}`)
        // userId should be the registered user's id (the session owner).
        assert(res.body.data.userId != null, 'expected userId present')
        ctx.chatSessionId = res.body.data.id
        ctx.chatUserToken = token
        ctx.chatUserId = res.body.data.userId
      },
    },
    {
      name: 'UC011 owner fetches session history (empty) → 200 + page shape',
      run: async (ctx) => {
        const res = await http.get(
          `/chat/sessions/${ctx.chatSessionId}/messages?page=1&size=50`,
          { token: ctx.chatUserToken })
        assert(res.status === 200, `expected 200 on history, got ${res.status}`)
        const page = res.body.data
        assert(page != null, 'expected page data present')
        // PageResult shape: {total, current, size, records:[...]}
        assert(Array.isArray(page.records), 'expected records array')
        assert(typeof page.total === 'number', `expected total number, got ${typeof page.total}`)
        // A freshly opened session has no messages yet.
        assert(page.records.length === 0,
          `expected empty history for a new session, got ${page.records.length} records`)
      },
    },
    {
      name: 'UC011 ownership: user B cannot read user A session → 403',
      run: async (ctx) => {
        // Register a second, distinct user. loadAndCheckOwnership throws
        // FORBIDDEN when the session exists but belongs to another user, so the
        // expected status is 403. (404 would only happen if the session id didn't
        // exist — tolerated defensively per the brief.)
        const regB = await registerWithCode(http, {
          username: 'chat2' + Date.now(),
          password: 'Pass1234!',
        })
        assert(regB.status === 200, `register B expected 200, got ${regB.status}`)
        const tokenB = regB.body.data.token
        const res = await http.get(
          `/chat/sessions/${ctx.chatSessionId}/messages?page=1&size=50`,
          { token: tokenB })
        assert(res.status === 403 || res.status === 404,
          `expected 403/404 (ownership denied), got ${res.status}`)
      },
    },
    {
      name: 'UC011 admin inbox lists open sessions → 200 + list',
      run: async (ctx) => {
        const res = await http.get('/admin/chat/sessions', { token: ctx.adminToken })
        assert(res.status === 200, `expected 200 on admin inbox, got ${res.status}`)
        assert(Array.isArray(res.body.data), 'expected inbox to be a list')
        // The session opened earlier should appear in the open-sessions inbox.
        const ids = res.body.data.map(s => s.id)
        assert(ids.includes(ctx.chatSessionId),
          `expected opened session ${ctx.chatSessionId} in inbox, got ${JSON.stringify(ids)}`)
      },
    },
    {
      name: 'UC011 admin reads a session message history → 200',
      run: async (ctx) => {
        const res = await http.get(
          `/admin/chat/sessions/${ctx.chatSessionId}/messages?page=1&size=50`,
          { token: ctx.adminToken })
        assert(res.status === 200, `expected 200 on admin history, got ${res.status}`)
        assert(res.body.data != null, 'expected admin history page data present')
        assert(Array.isArray(res.body.data.records), 'expected admin records array')
      },
    },
  ],
}
