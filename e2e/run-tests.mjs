import { env } from './lib/env.mjs'
import { setLog, http } from './lib/http.mjs'
import { writeResults } from './lib/reporter.mjs'

// Pre-flight: is the live system reachable?
async function preflight() {
  try {
    const res = await http.get('/products')
    if (res.status !== 200) throw new Error(`unexpected status ${res.status}`)
  } catch (e) {
    console.error(`❌ Cannot reach the system at ${env.BASE_URL}. Is the VM running?`)
    console.error(`   (${e.message})`)
    process.exit(1)
  }
}

// Run a single test in isolation; catch → record FAIL/ERROR; suite continues.
async function runTest(file, t, ctx, log) {
  const started = Date.now()
  const logStart = log.length
  const result = { file, name: t.name, knownIssue: t.knownIssue || null, status: 'PASS', durationMs: 0, error: null }
  try {
    await t.run(ctx)
    // If knownIssue is set and the test passed asserting buggy behavior, that's expected.
  } catch (e) {
    result.status = e.name === 'AssertionError' ? 'FAIL' : 'ERROR'
    result.error = e.message
  }
  result.durationMs = Date.now() - started
  result.log = log.slice(logStart).join('\n')
  return result
}

async function main() {
  await preflight()
  console.log(`✓ System reachable at ${env.BASE_URL}\n`)

  // testFiles: array of { name, tests } — populated as tasks add files.
  const testFiles = []
  const runLog = []
  setLog((line) => runLog.push(line))

  const ctx = {} // shared state across all tests
  const results = []
  for (const file of testFiles) {
    console.log(`\n=== ${file.name} ===`)
    for (const t of file.tests) {
      const r = await runTest(file.name, t, ctx, runLog)
      results.push(r)
      const mark = r.status === 'PASS' ? '✓ PASS' : '✗ ' + r.status
      console.log(`  ${mark} — ${t.name}` + (r.error ? `  (${r.error})` : ''))
    }
  }

  const summary = writeResults(results, runLog)
  console.log(`\n=== Summary: ${summary.passed}/${summary.total} passed, ${summary.failed} failed ===`)
  console.log(`Run log:    ${summary.runLog}`)
  console.log(`Bug report: ${summary.bugReport}`)
}

main().catch((e) => { console.error('Fatal:', e); process.exit(1) })
