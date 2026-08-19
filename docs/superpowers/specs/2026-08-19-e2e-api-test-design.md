# E2E API Test Suite — Design Spec

> Date: 2026-08-19
> Status: Approved (brainstormed 2026-08-18 → 2026-08-19)
> Goal: Automated black-box tests that drive the **live deployed system**, log every step, and report bugs — so manual click-testing (which can't cover edge cases) is replaced by repeatable, logged, automated verification.

## 1. Purpose & scope

The system is deployed and live on the VirtualBox VM at `http://localhost:8888`. Manual click-testing found real bugs but can't cover all operations or edge cases. This spec defines an **API-level (HTTP) end-to-end test suite** that:

- Drives the live system through its HTTP endpoints (black-box — no knowledge of internals).
- Targets the **core flows that exist** (auth, product CRUD, sale-open, checkout→pay→cancel, idempotency, concurrency). Does not invent coverage for unbuilt features (the system is still naive).
- **Documents known bugs first** (tests assert current buggy behavior → green suite that records defects), then flips each test to assert the *fixed* behavior as bugs are fixed.
- Produces two artifacts per run: a timestamped **run log** (full trace) and a **bug report** (failures with reproduction steps).

### What it is NOT

- Not a unit test of backend internals (those exist: 29 backend + 19 frontend, green).
- Not a browser/UI test (no Playwright/Selenium) — API-level only, by decision.
- Not merged into `mvn test` — a standalone Node script that runs against the live VM.

## 2. Approach: standalone Node script

A standalone Node.js suite under a new top-level `e2e/` directory. Zero new dependencies (Node's `fetch` is built-in). Tests the whole stack (Nginx → backend → MariaDB → Redis) exactly as a user experiences it.

## 3. Structure

```
e2e/
  run-tests.mjs        # runner: executes all test files, collects results
  tests/
    auth.test.mjs      # register / login / me
    products.test.mjs  # list / get / admin create / admin edit (bug 1) / delete
    sale.test.mjs      # admin open sale
    checkout.test.mjs  # checkout before sale-open (bug 2), checkout after, pay, cancel, status, idempotency, sold-out
    concurrency.test.mjs  # headline: N concurrent buyers, K succeed, no oversell
  lib/
    http.mjs           # fetch wrapper: base URL, JSON in/out, Bearer token, logs every request
    reporter.mjs       # writes run-log + bug-report to out/
    env.mjs            # BASE_URL, admin creds, timeouts (config, no hardcoded values)
  out/                 # gitignored — run logs + bug reports land here
  .gitignore           # ignores out/
```

### Components

- **`lib/http.mjs`** — wraps `fetch` with the base URL (`http://localhost:8888/api`), auto-attaches the JWT from the test context, parses JSON, and logs every request + response to the run log. Returns `{ status, body }`.
- **`lib/reporter.mjs`** — takes the collected per-test results and writes (a) the run log (full trace) and (b) the bug report (failures only).
- **`lib/env.mjs`** — config: `BASE_URL`, admin credentials, per-request timeout, poll timeout. No hardcoded values in tests; read from here.
- **`run-tests.mjs`** — the entry point (`node e2e/run-tests.mjs`). Pre-flight ping (is the VM reachable?), then runs all `tests/*.test.mjs` files sequentially, times each, captures pass/fail/error, passes results to the reporter.

### Test case shape

Each `tests/*.test.mjs` exports an array of test cases:

```js
export const tests = [
  {
    name: 'register a new user',
    knownIssue: null,              // null = expected to pass; string = documents a known bug
    run: async (ctx) => {
      const res = await http.post('/auth/register', { username: ..., password: ... })
      assert(res.status === 200, `expected 200, got ${res.status}`)
      assert(res.body.success === true, 'expected success:true')
      // ctx holds shared state (tokens, created IDs) across tests
    }
  }
]
```

- `ctx` — shared mutable context object (holds admin token, created product id, etc.) passed through all tests in a file.
- `assert(condition, message)` — throws on false; the runner catches → records `[FAIL]` with the message; suite continues.
- `knownIssue` — optional note; when set, the test is expected to pass *asserting current (buggy) behavior*. The bug report seeds its "Likely cause" from this. When the bug is fixed, the test is rewritten to assert correct behavior and `knownIssue` cleared.

## 4. Test cases

23 tests across 5 files. The two known bugs are documented (#13, #15); the headline concurrency guarantee is #23.

### auth.test.mjs
1. Register a new user → 200, success, role:USER.
2. Register duplicate username → 409, success:false.
3. Login as admin (admin/admin123) → 200, returns token (JWT), role:ADMIN. *(Stores token in ctx.)*
4. Login wrong password → 401.
5. GET /auth/me with valid token → 200, returns admin user.
6. GET /auth/me without token → 401.

### products.test.mjs
7. List products (no auth) → 200, array contains seeded Flash Widget + Demo Gadget.
8. Get product by id (no auth) → 200, fields correct.
9. Get nonexistent product → 404.
10. Admin creates a product → 200, returns product with generated id. *(Stores id in ctx.)*
11. Non-admin tries to create product → 403.
12. Admin edits a NON-flash product (name/price/stock) → 200, changes persisted (re-GET confirms).
13. **Admin edits a FLASH-SALE product with flashSaleAt set → documented bug: expect 500** (date-format mismatch: frontend sends ISO `2026-08-19T15:30`, backend Jackson expects `yyyy-MM-dd HH:mm:ss`). `knownIssue` set. Flip to expect 200 when fixed.
14. Admin deletes the product from #10 → 200, then GET → 404.

### sale.test.mjs
15. **Checkout a product whose sale was never opened → documented behavior: expect 503 "stock not initialized"** (fail-closed by design — NOT a bug, but confusing UX). `knownIssue` notes it's intended; this test documents it so behavior is pinned.
16. Admin opens the flash sale (POST /admin/sales/{id}) → 200. Verify indirectly (re-checkout now succeeds).

### checkout.test.mjs
17. User checkout after sale open → 202, returns orderId, status:"queued".
18. Poll order status until "pending" (max ~15s, awaitility-style) → queued→pending transition.
19. Pay the order → 200, status PAID; verify DB stock decremented (re-GET product).
20. Cancel another queued order → 200, status CANCELLED, stock re-credited (re-GET product, stock back up).
21. Checkout with duplicate idempotency key → same order returned (idempotency), not a duplicate.
22. Checkout more than available stock → 409 (sold out), no order created.

### concurrency.test.mjs — the headline
23. **Load/mutex: 50 concurrent buyers, stock=5** → fire 50 simultaneous checkouts (Promise.all). Assert: exactly 5 return 202, the rest get 409 (sold out) — never more than 5 succeed, never oversell. Final product stock = 0. The Taobao-seckill proof, run against the real deployment.

## 5. Output artifacts (per run)

Written to `e2e/out/` (gitignored).

### Run log — `run-log-<timestamp>.md`
Full timestamped trace: summary line (total/passed/failed/duration), then per-test section showing every HTTP call (method, path, status, request body, response body) and each assertion (✓/✗). When a test fails, the log IS the reproduction.

### Bug report — `bug-report-<timestamp>.md`
Failures only, one entry per failure ("Bug N"), with: severity, test name, what happened, expected, likely cause (seeded from the test's `knownIssue` note), and a numbered reproduction using the exact failing request. Clean runs produce "0 failures — all tests passed."

### Console summary
Live one-line-per-test progress (PASS/FAIL + name) during the run, then the summary line.

## 6. Error handling & robustness

- **Pre-flight:** runner pings `GET /api/products`; if unreachable → clear message "Cannot reach the system at http://localhost:8888. Is the VM running?" and exit. No point running 23 tests against nothing.
- **Per-test isolation:** each test wrapped in try/catch; a throw → recorded `[ERROR]` for that test, suite continues. One broken test doesn't hide the others.
- **Test data cleanup:** tests create entities with unique (timestamp-suffixed) names and clean up (delete created product, etc.) so re-running doesn't pollute the DB or trip unique-constraint collisions. Concurrency test creates + deletes its own product.
- **Timeouts:** every `fetch` has a timeout (e.g., 10s); the status-poll test (#18) has a bounded loop (max ~15s).
- **Suite idempotency:** running twice yields the same result (no "first pass, second fail" from leftover data) — enforced by cleanup + unique names.

## 7. Out of scope / deferred

- Browser/UI tests (Playwright) — may add later for the few key frontend flows; not now.
- Performance/load beyond the 50-buyer mutex test — the concurrency *correctness* is the goal, not throughput benchmarking.
- Testing features not yet built — the suite targets existing flows only.
- CI integration — runs manually via `node e2e/run-tests.mjs` against the live VM; not wired into any pipeline yet.

## 8. Open questions resolved during brainstorming

- **Test level:** API-level (HTTP scripts), not browser-level. (User decision.)
- **Bug policy:** tests document bugs first (assert current behavior → green suite), then flip to assert fixed behavior as bugs are fixed. (User decision.)
- **Tooling:** standalone Node script (Approach A), not Rest-Assured/Java or Python. Zero new deps, tests the real deployment, produces the requested logs + bug report. (Recommended + accepted.)
