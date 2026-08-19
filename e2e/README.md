# E2E API Test Suite

Black-box HTTP tests that drive the **live deployed** Frame Shop system (`http://localhost:8888`), log every step, and report bugs.

## Prerequisites

- The VirtualBox VM is running and the system is live (Nginx + backend + MariaDB + Redis).
- Node.js installed (already a frontend dependency).

## Run

```bash
node e2e/run-tests.mjs
```

Console shows live pass/fail per test. On finish:

- `e2e/out/run-log-<timestamp>.md` — full trace (every HTTP request/response + assertions).
- `e2e/out/bug-report-<timestamp>.md` — failures only, with reproduction.

## What it tests

26 tests across 5 files (auth 6 + products 9 + sale 3 + checkout 7 + concurrency 1): auth, products (incl. a documented defect history — the flash-sale edit once returned 500 from a product-cache ObjectMapper missing `java.time` deserializers; that bug is now FIXED and the test asserts the correct 200), sale-open, checkout→pay→cancel→idempotency (incl. documented behavior: checkout before sale-open → 503), and the headline concurrency test (50 concurrent buyers, stock=5 → exactly 5 succeed, no oversell). The suite is currently 26/26 green after the backend fixes it surfaced.

## Bug policy

Tests document known bugs first (assert current buggy behavior → green suite that records defects). As bugs are fixed, flip the test to assert the correct behavior and clear its `knownIssue` note.

## Config

`lib/env.mjs` — base URL, admin creds, timeouts. Change here, not in tests.
