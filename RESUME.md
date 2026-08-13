# RESUME — Frame Shop build (Subagent-Driven Development)

> Last updated end of day 2026-08-13. Working directory: `C:\Users\tcmao\Desktop\newRepo`
> Git: initialized, branch `frame-shop`, working tree CLEAN. Baseline: `b2bb879`.
> **Implementing the Frame Shop plan via the SDD skill. Read this first tomorrow.**

## WHERE WE ARE — exactly

The Frame Shop V1 plan (`docs/superpowers/plans/2026-08-12-frame-shop.md`, 8 tasks) is being executed task-by-task with the subagent-driven-development skill: dispatch implementer → review → fix if needed → next task. **Half the backend is done and verified.**

### ✅ Tasks 1-4 COMPLETE (each review-clean)

| Task | Commit(s) | Notes |
|---|---|---|
| 1: Data model + schema | `5e878f2` | User/Product/Order/OrderItem + enums + mappers + decrementStock. Clean. |
| 2: Real auth + role=ADMIN | `ae42f95` | JWT role claim, SecurityUser.from reads role, hasRole rules, UserDetailsService, register. Clean (3 minor deferred). |
| 3: Product CRUD + Redis cache + error codes | `f35fbd4` + `0afdffc` | + fix round 1: uploads permitAll + Redis-down DB fall-through. Clean (4 minor deferred). |
| 4: Synchronous checkout + idempotency | `3742f2b` + `a67ae84` | + fix round 1: null-guard on selectByIdempotencyKey. Clean (3 minor deferred). |

**Test count: 9/9 passing** (`mvn clean test` on `frame-shop` branch at `a67ae84`).

### ⏸ NEXT — Task 5 (Redis Lua gate + Stream + consumer)

**This is where tomorrow starts.** Task 5 is the hardest task — the Taobao-model heart: the Redis Lua stock gate + Stream buffer + consumer-group consumer (at-least-once via XACK-after-commit) + admin sale endpoint. It layers the async/Redis fast path onto Task 4's synchronous correctness core.

## TOMORROW, STEP BY STEP

1. **Open a fresh terminal from the Start menu** (so PATH changes from earlier apply). Verify the toolchain:
   ```bash
   java -version && mvn -version && mariadb --version && redis-cli ping
   ```
   (If `mariadb`/`redis-cli`/`mvn`/`java` aren't found, the PATH isn't sticky — prepend the bin dirs: `export PATH="/c/Program Files/Java/jdk-21.0.11/bin:/d/apache-maven-3.9.16/bin:/c/Program Files/MariaDB 12.3/bin:/c/Program Files/Redis:$PATH"; export JAVA_HOME="/c/Program Files/Java/jdk-21.0.11"`)
2. **Verify MariaDB + Redis are running** (services should auto-start, but check):
   ```bash
   netstat -ano | grep LISTEN | grep -E ":3306|:6379"   # both should show LISTENING
   ```
3. **Resume the SDD execution** — continue from Task 5. The SDD skill's workspace + ledger already exist:
   - Ledger: `.superpowers/sdd/2026-08-12-frame-shop/progress.md` (read this — it's the recovery map; Tasks 1-4 marked complete, Task 5 marked "IN PROGRESS — NOT STARTED")
   - The plan: `docs/superpowers/plans/2026-08-12-frame-shop.md`
   - **Invoke `superpowers:subagent-driven-development`** (it's the skill in progress) — or just continue the dispatch/review/fix loop manually using its scripts.
4. **Task 5 dispatch steps** (per the SDD process):
   - `git rev-parse HEAD` → record BASE (should be `a67ae84`)
   - Run the task-brief script: `bash "/c/Users/tcmao/.claude/plugins/cache/claude-plugins-official/superpowers/6.2.0/skills/subagent-driven-development/scripts/task-brief" "docs/superpowers/plans/2026-08-12-frame-shop.md" 5`
   - Dispatch a general-purpose implementer subagent with the brief path + context (the brief's Task 5 is large: `stock_gate.lua`, `StockGateService`, `StreamConsumerService` with consumer group + XACK-after-commit + XAUTOCLAIM, `AdminSaleController`, refactor `CheckoutServiceImpl` to the async hot path). Give it the environment facts (Redis at :6379, the PATH-prepend line, Task 4's `CheckoutService.checkout` returns `status="pending"` which Task 5 changes to `"queued"`).
   - On DONE: generate review package (`scripts/review-package ... BASE HEAD`), dispatch task reviewer.
   - Fix loop if needed (≤5 rounds), then mark complete + ledger entry + next task.
5. **Remaining after Task 5:** Task 6 (payment + status + cancel), Task 7 (integration + load/mutex tests), Task 8 (frontend), then the final whole-branch review. Then Linux deployment (separate follow-up plan).

## KEY ANCHORS for Task 5 (from the plan + Task 4)

- **Task 4's `CheckoutService.checkout` returns `status="pending"` (synchronous V1).** Task 5 changes the hot path to: Lua gate → `XADD` to `orders:pending` stream → `order_status:{id}="queued"` → return `202 {orderId, status:"queued"}`. The durable-write logic (decrementStock + insert + idempotency) moves into `StreamConsumerService`.
- **The idempotency-key UNIQUE constraint remains the no-double-order authority** (Task 4 established it).
- **Consumer group is mandatory** (not bare `XREAD`) — `XGROUP CREATE` + `XREADGROUP` + `XACK`-only-after-`@Transactional`-commit + `XAUTOCLAIM`/`XPENDING` recovery. This is the at-least-once construction.
- **Redis 5.0.14.1** is installed (no `XAUTOCLAIM` — it's 6.2+; use `XPENDING` + `XCLAIM`, or re-read this consumer's PEL via `XREADGROUP ... ID 0`). Note this to the implementer.
- The Lua script is two-pass (check-all-then-decr-all) with `tonumber()`. The brief has the literal script.

## ENVIRONMENT FACTS (permanently configured)

| Thing | Value |
|---|---|
| Git branch | `frame-shop` (baseline `b2bb879` on `main`) |
| Backend port | 8081 |
| Frontend port | 5173 |
| MariaDB | root pw `frame_dev`, db `frame_db` (4 tables: user/product/orders/order_item) |
| Redis | :6379 (Windows service), Lua + Streams + consumer groups verified |
| JDK / Maven / MariaDB / Redis | all on user PATH (fresh terminal from Start menu) |
| SDD workspace | `.superpowers/sdd/2026-08-12-frame-shop/` (ledger + briefs + reports + review diffs; git-ignored) |

## KEY FILES

| File | Why |
|---|---|
| `docs/superpowers/plans/2026-08-12-frame-shop.md` | the 8-task implementation plan |
| `.superpowers/sdd/2026-08-12-frame-shop/progress.md` | **the SDD ledger — read this first; recovery map** |
| `docs/superpowers/specs/2026-08-12-frame-shop-flash-sale-design.md` | the design spec (revised after adversarial review) |
| `frame-backend/.../service/impl/CheckoutServiceImpl.java` | Task 4's synchronous checkout — Task 5 refactors to async |
| `frame-backend/.../security/SecurityConfig.java` | has role=ADMIN rules + uploads permitAll (Tasks 2-3) |

`RESUME.md` is this current-state summary. The SDD ledger is the authoritative recovery map if context is compacted — trust it + `git log` over recollection.
