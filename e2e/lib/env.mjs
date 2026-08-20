// Single source of config — no test hardcodes these values.
// The live deployed VM is reached at http://localhost:8888 (Nginx port-forward 8888→guest 80).
// Nginx strips the /api prefix, so /api/auth/login → backend /auth/login.
// Override BASE_URL with E2E_BASE_URL for local runs (e.g. http://localhost:8081 — no /api prefix).
export const env = {
  BASE_URL: process.env.E2E_BASE_URL || 'http://localhost:8888/api',
  ADMIN: { username: 'admin', password: 'admin123' },
  TIMEOUT_MS: 10_000,      // per-request fetch timeout
  POLL_TIMEOUT_MS: 15_000, // max wait for queued→pending status transition
  POLL_INTERVAL_MS: 500,
}
