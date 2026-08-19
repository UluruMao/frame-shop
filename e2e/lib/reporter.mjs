import { writeFileSync, mkdirSync } from 'node:fs'
import { dirname } from 'node:path'

// timestamp string for filenames: 2026-08-19T143201
function stamp(d = new Date()) {
  const p = (n, l = 2) => String(n).padStart(l, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`
}

// results: [{ file, name, knownIssue, status: 'PASS'|'FAIL'|'ERROR', durationMs, error }]
// runLog: array of raw log lines (the full trace)
export function writeResults(results, runLog) {
  const dir = 'e2e/out'
  mkdirSync(dir, { recursive: true })
  const ts = stamp()

  // 1. Run log — full trace
  const passed = results.filter(r => r.status === 'PASS').length
  const failed = results.filter(r => r.status !== 'PASS').length
  const total = results.length
  const dur = results.reduce((s, r) => s + r.durationMs, 0)
  const logLines = [
    `# E2E Run — ${ts}`,
    `Total: ${total} | Passed: ${passed} | Failed: ${failed} | Duration: ${(dur / 1000).toFixed(1)}s`,
    '',
    ...runLog,
  ]
  writeFileSync(`${dir}/run-log-${ts}.md`, logLines.join('\n'))

  // 2. Bug report — failures only
  const failures = results.filter(r => r.status !== 'PASS')
  const bugLines = [
    `# Bug Report — ${ts}`,
    `${failures.length} failure(s) found.`,
    '',
  ]
  failures.forEach((f, i) => {
    bugLines.push(`## Bug ${i + 1}: ${f.name} [${f.file}]`)
    bugLines.push(`- **Status:** ${f.status}`)
    if (f.knownIssue) bugLines.push(`- **Documented issue:** ${f.knownIssue}`)
    bugLines.push(`- **Error:** ${f.error || '(no detail)'}`)
    bugLines.push('')
  })
  if (failures.length === 0) bugLines.push('All tests passed — no bugs found.')
  writeFileSync(`${dir}/bug-report-${ts}.md`, bugLines.join('\n'))

  return { runLog: `${dir}/run-log-${ts}.md`, bugReport: `${dir}/bug-report-${ts}.md`, passed, failed, total }
}
