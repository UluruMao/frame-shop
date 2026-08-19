import { env } from './env.mjs'

// A log sink set by the runner so every HTTP call is recorded in the run log.
let logSink = () => {}
export function setLog(fn) { logSink = fn }

function truncate(s, max = 2000) {
  // JSON.stringify(undefined) returns undefined (not a string), so guard it.
  const str = s === undefined ? '(no body)' : (typeof s === 'string' ? s : JSON.stringify(s))
  return str.length > max ? str.slice(0, max) + `…(${str.length} bytes)` : str
}

// request(method, path, payload, { token })
//   - POST/PUT: payload is the request body (sent as JSON). Token optional via 4th arg.
//   - GET/DELETE: payload omitted; { token } is the options bag.
// This matches the design spec's call shape: http.post(path, body) / http.get(path, { token }).
async function request(method, path, payload, { token } = {}) {
  const url = `${env.BASE_URL}${path}`
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers['Authorization'] = `Bearer ${token}`
  const opts = { method, headers, signal: AbortSignal.timeout(env.TIMEOUT_MS) }
  const body = payload
  if (body !== undefined) opts.body = JSON.stringify(body)

  const started = Date.now()
  let res
  try {
    res = await fetch(url, opts)
  } catch (e) {
    logSink(`  ${method} ${path}  → ERROR: ${e.message}`)
    throw e
  }
  // Always parse JSON (even on error — the envelope is consistent).
  let parsed
  const text = await res.text()
  try { parsed = JSON.parse(text) } catch { parsed = text }
  const ms = Date.now() - started
  logSink(`  ${method} ${path}  → ${res.status}  (${ms}ms)`)
  logSink(`    request:  ${truncate(body)}`)
  logSink(`    response: ${truncate(parsed)}`)
  return { status: res.status, body: parsed }
}

export const http = {
  get:  (path, opts) => request('GET',    path, undefined, opts),
  post: (path, body) => request('POST',   path, body),
  put:  (path, body) => request('PUT',    path, body),
  del:  (path, opts) => request('DELETE', path, undefined, opts),
}
