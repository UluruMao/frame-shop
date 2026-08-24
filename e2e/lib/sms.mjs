import { execFileSync } from 'node:child_process'

// redis-cli is on PATH in the dev environment (resolves to the Windows install at
// C:\Program Files\Redis\redis-cli). Using execFileSync with an args array avoids any
// shell-path/quoting issues (execSync with a string command would route through cmd.exe,
// which can't resolve the Git-Bash-style "/c/..." path). Dev Redis is passwordless localhost.

/**
 * Register a user the way UC001 expects (5-arg: username+password+phone+code+agreed),
 * but pre-seed the verification code in Redis so the e2e harness can supply it without
 * driving a real SMS flow. Mirrors CheckoutFlowIntegrationTest.java:68-74 /
 * CheckoutWithCoinsIntegrationTest.java:83-89.
 *
 * The dev LoggingVerificationProvider.verify() reads sms:code:REGISTER:{phone}
 * (5-min TTL, one-shot: verify deletes the key). So we SET that key with a known code
 * before the POST; the backend then verifies against our pre-seeded value.
 *
 * The backend enforces (UserServiceImpl.register):
 *  - username: 4-25 chars, ALPHANUMERIC ONLY (no underscore/special) — pass 'e2e1234567'
 *  - password: ≥8 chars, ≥2 of {upper, lower, special} — pass 'Pass1234!'
 *  - phone: unique per call (duplicate phone → 409 "phone already registered")
 *  - code: must match the seeded value (we use '123456')
 *  - agreed: must be true
 *
 * @param {object} http - the e2e http helper ({ post(path, body, opts) → { status, body } })
 * @param {{ username: string, password: string, phoneSuffix?: string }} opts
 * @returns {Promise<{ phone: string, status: number, body: any }>}
 */
export async function registerWithCode(http, { username, password, phoneSuffix }) {
  // Unique phone per call. Date.now() has ms resolution; in a tight synchronous loop
  // (e.g. the concurrency suite's 50-buyer registration), multiple iterations can share
  // the same millisecond → same phone → 409 "phone already registered". An optional
  // phoneSuffix (a small counter) disambiguates within the same ms.
  const phone = '139' + String(Date.now()).slice(-8) + (phoneSuffix ? String(phoneSuffix) : '')
  const code = '123456'
  // Pre-seed the code the backend will verify against (5-min TTL, one-shot).
  execFileSync('redis-cli', ['SET', `sms:code:REGISTER:${phone}`, code, 'EX', '300'], { stdio: 'ignore' })
  const res = await http.post('/auth/register', { username, password, phone, code, agreed: true })
  return { phone, status: res.status, body: res.body }
}
