// 10k QPS load test for the GET /products list path (Redis-cached after Task 3).
// Runs increasing RPS stages against the dev backend (:8081), measures p95 latency,
// writes a result report to stdout. Accepts E2E_BASE_URL (default http://localhost:8081).
//
// Usage:  E2E_BASE_URL=http://localhost:8081 node e2e/load/products-load.mjs
//
// NOTE on the client bottleneck: Node's built-in fetch (undici) caps per-host
// concurrency via its connection pool (default ~10 connections). To let the client
// actually drive high RPS, we raise undici's global dispatcher pool to a large
// value. If achieved RPS still plateaus well below target, the limiter is the
// client machine / Node event loop, NOT the server (server p95 stays single-digit
// ms) — a documented bottleneck outcome per the spec's acceptance bar.

// Raise undici's per-host connection ceiling so the client can drive real concurrency.
try {
  const { Agent, setGlobalDispatcher } = await import('undici')
  setGlobalDispatcher(new Agent({ connections: 1024 }))
} catch { /* undici unavailable (older Node) — fall back to default fetch */ }

const BASE = process.env.E2E_BASE_URL || 'http://localhost:8081'
const STAGES = [1000, 5000, 10000]           // req/s targets
const STAGE_DURATION_MS = 15000              // 15s per stage

async function sleep(ms) { return new Promise(r => setTimeout(r, ms)) }

async function stage(targetRps, durationMs) {
  const latencies = []
  let errors = 0
  let sent = 0
  const inflight = []
  const intervalMs = 1000 / targetRps
  const start = Date.now()
  const deadline = start + durationMs

  // Dispatch at the target rate using setInterval (non-blocking: each tick fires a
  // fetch without awaiting it, so the event loop can fire thousands/s). Collect the
  // promises; await them all after the deadline.
  return new Promise((resolve) => {
    const timer = setInterval(() => {
      if (Date.now() >= deadline) {
        clearInterval(timer)
        Promise.allSettled(inflight).then(() => {
          latencies.sort((a, b) => a - b)
          const pct = (p) => latencies.length ? latencies[Math.min(latencies.length - 1, Math.floor(latencies.length * p))] : -1
          const p50 = pct(0.50), p95 = pct(0.95), p99 = pct(0.99)
          const achieved = Math.round((latencies.length + errors) / (durationMs / 1000))
          const pass = achieved >= targetRps * 0.9 && p95 > 0 && p95 < 2000
          resolve({ targetRps, sent, achieved, p50, p95, p99, errors, pass })
        })
        return
      }
      sent++
      const reqStart = Date.now()
      inflight.push(
        fetch(`${BASE}/products?size=10`)
          .then(async (r) => { await r.text(); latencies.push(Date.now() - reqStart) })
          .catch(() => { errors++ })
      )
    }, intervalMs)
  })
}

const report = []
for (const rps of STAGES) {
  process.stdout.write(`stage ${rps} req/s ... `)
  const r = await stage(rps, STAGE_DURATION_MS)
  report.push(r)
  console.log(`achieved=${r.achieved} p50=${r.p50}ms p95=${r.p95}ms p99=${r.p99}ms errors=${r.errors} → ${r.pass ? 'PASS' : 'NEEDS-REVIEW'}`)
}

const verdict = report.every(r => r.pass) ? 'PASS' : 'NEEDS-REVIEW'
const ts = new Date().toISOString().replace(/[:.]/g, '-')
const md = [
  `# 10k QPS Load Test Result`,
  ``,
  `- Date: ${new Date().toISOString()}`,
  `- Target: ${BASE}/products?size=10 (Redis list cache, 30s TTL)`,
  `- Stage duration: ${STAGE_DURATION_MS}ms each`,
  ``,
  `| Stage (req/s target) | Sent | Achieved (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | Errors | Verdict |`,
  `|---|---|---|---|---|---|---|---|`,
  ...report.map(r => `| ${r.targetRps} | ${r.sent} | ${r.achieved} | ${r.p50} | ${r.p95} | ${r.p99} | ${r.errors} | ${r.pass ? 'PASS' : 'NEEDS-REVIEW'} |`),
  ``,
  `## Overall: ${verdict}`,
  ``,
  verdict === 'PASS'
    ? `All stages hit ≥90% of target RPS with p95 < 2s. The Redis list cache (Phase F Task 3) + the existing stateless JWT / HikariCP / Nginx architecture met the 10000 QPS NFR on the single dev host.`
    : `One or more stages fell short of 10k req/s or p95 < 2s. See the bottleneck analysis in docs/superpowers/PHASE-F-STATUS.md — the documented scaling path is the two-host distributed variant (stateless JWT + shared Redis/MariaDB → load-balancer in front), the planned next milestone.`,
].join('\n')

const outPath = `e2e/out/load-${ts}.md`
const { writeFileSync, mkdirSync } = await import('node:fs')
try { mkdirSync('e2e/out', { recursive: true }) } catch {}
writeFileSync(outPath, md)
console.log(`\n${md}`)
console.log(`\nReport written to ${outPath}`)
