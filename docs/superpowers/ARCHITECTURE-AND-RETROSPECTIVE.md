# Frame Shop — Software Architecture, Hardest Tasks, and What's Left

> A whole-project retrospective for the Frame Shop e-commerce platform (`C:\Users\tcmao\Desktop\newRepo`), written after Phase F (the final phase of the UC000-UC018 fulfillment) merged to main on 2026-08-24. This is the high-level map: the architecture, the hardest things we built, and what remains.

---

## 1. What this project is

Frame Shop is a full e-commerce storefront built on top of a Spring Boot + React template. It started as a flash-sale (Taobao-seckill-model) MVP and grew into a complete platform satisfying every use case in `use case/use cases.txt` (UC000-UC018) plus the non-functional requirements (HTTPS, operation logging, 10000 QPS, image lazy-load), minus OAuth2.0 (explicitly deferred to save time — existing JWT auth retained).

**The stack:**
- **Backend:** Spring Boot 4.1 / Java 21 / MyBatis-Plus / MariaDB / Redis / JWT (stateless, HS256). Spring Security with URL-matcher `hasRole` rules (no method security). AOP for operation logging.
- **Frontend:** React 18 / Vite / Tailwind / Zustand (persisted auth store) / Axios (response interceptor unwraps the `Result.data` envelope).
- **Tests:** JUnit + Mockito + Awaitility (backend), Vitest + Testing Library (frontend), a hand-rolled Node `fetch` e2e runner (67 cases), a STOMP `WebSocketStompClient` integration test, a Node load-test script.
- **Deploy:** single VirtualBox VM (Ubuntu, Nginx + systemd `frame-backend.service` + MariaDB + Redis), port-forward 8888→80.

**The build was decomposed bottom-up into 6 phases, each its own spec→plan→subagent-driven-build→test→merge cycle:**
| Phase | Theme | UCs | Merged |
|---|---|---|---|
| A | Foundation & security (User fields, SMS, verification, lockout, password reset, phone rebind, profile, op-log, HTTPS) | UC001/002/015/016/017/018 + NFR HTTPS/op-log | `dc66299` |
| B | Catalog (keyword search, multi-criteria sort, product detail page) | UC000 | `ef4a694` |
| C | Order lifecycle & refunds (SHIPPED/DELIVERED/REFUNDED, 30-min expiry, cancel+refund, admin mgmt) | UC004-009/014 | `f64f9e8` |
| D | Reviews (3-D star ratings, image upload, edit) | UC010 | `8b44d19` |
| E | Coins economy (daily-login, review reward, FIFO expiry, checkout discount) | UC012 + UC005 coin branch | `a2e51f0` |
| F | Live chat (WebSocket/STOMP) + 10k QPS proof + skipped-bug cleanup | UC011 + NFR perf | `bfa5dd5` |

**Final test state:** backend **176/176**, frontend **54/54**, e2e **67/67** — all genuine, 0 skipped.

---

## 2. Software architecture

### 2a. The layered architecture

```
┌─────────────────────────────────────────────────────────────────┐
│ Browser (React SPA: Products / Cart / Checkout / Orders /       │
│   Reviews / Coins / Chat / Admin / Profile)                      │
│   - Axios (REST, /api → :8081)   - @stomp/stompjs (WS, /ws-chat) │
└──────────────┬───────────────────────────┬──────────────────────┘
               │ REST (JWT in Authorization │ STOMP (JWT in CONNECT frame)
               │ header)                   │ over SockJS
       ┌───────▼────────┐          ┌───────▼────────┐
       │  Spring Security│          │ WebSocketConfig│
       │  filter chain    │          │ + AuthChannel  │
       │  (JWT filter,    │          │  Interceptor   │
       │  rate-limit)     │          │  (JWT parse →  │
       └───────┬────────┘          │  SecurityUser) │
               │                    └───────┬────────┘
       ┌───────▼────────────────────────────▼────────────────┐
       │              Controllers (REST + @MessageMapping)    │
       │  Auth, Product, Order, AdminOrder, AdminSale,         │
       │  Checkout, Coin, Review, Profile, Verification,       │
       │  OperationLog, Chat, AdminChat, ChatStomp            │
       └───────┬─────────────────────────────────────────────┘
               │
       ┌───────▼─────────────────────────────────────────────┐
       │              Services (the business logic)            │
       │  AuthService, ProductService, OrderService,           │
       │  CheckoutService, StreamConsumerService,             │
       │  CoinService, ReviewService, ChatService,             │
       │  StockGateService, OrderLifecycleScheduler, ...       │
       └──┬──────────┬──────────────┬───────────────┬─────────┘
          │          │              │               │
     ┌────▼───┐ ┌────▼─────┐  ┌─────▼─────┐  ┌──────▼──────┐
     │ MariaDB│ │  Redis   │  │ MyBatis-  │  │  Lua scripts │
     │ (10    │ │ (cache, │  │ Plus      │  │  (stock gate)│
     │ tables)│ │  queue, │  │ mappers   │  │              │
     │        │ │  sms)   │  │ (7+ XML)  │  │              │
     └────────┘ └──────────┘  └───────────┘  └─────────────┘
```

### 2b. The data model (10 tables)
- `user` — JWT auth, role USER/ADMIN, phone/nickname/avatar/signature/status(ACTIVE/LOCKED)/failed_attempts.
- `product` — name, price_in_cents (integer cents), stock, category, is_flash_sale/flash_sale_at, description.
- `orders` — 9 statuses (PENDING/PAID/CANCELLED/FAILED/SHIPPED/DELIVERED/COMPLETED/REFUND_PENDING/REFUNDED) + refund_status + 10 lifecycle columns (tracking_number, shipped_at, delivered_at, paid_at, paid_in_cents, ...). idempotency_key UNIQUE.
- `order_item` — name/price snapshots.
- `review` + `review_image` — 3-D stars (quality/logistics/service), unique-per-order-product, ≤9 images.
- `coin_ledger` — append-only rows; `amount` (+earned/-spent), `source`, `idem_key` UNIQUE, `expires_at` doubles as a state flag, `ref_credit_expires_at` snapshot for refund-return.
- `operation_log` — AOP-written, one row per mutating endpoint.
- `chat_session` + `chat_message` — UC011; `chat_session` has a virtual generated column `open_user_key = IF(status='OPEN', user_id, NULL)` + `UNIQUE uk_session_open_user` (enforces one-open-session-per-user at the DB level).

### 2c. The concurrency model — the headline (Taobao seckill, defense in depth)
This is the most important part of the architecture. Limited-quantity products, many buyers at once, **overselling impossible**:

1. **Tier 1 — Redis Lua script (the primary stock gate).** A two-pass all-or-nothing Lua script atomically checks-all-then-decrements-all. Shields MariaDB from the burst. Fail-closed on Redis outage (503, never bypasses the gate).
2. **Tier 2 — Redis Stream (`orders:pending`) via consumer group (`XGROUP`/`XREADGROUP`/`XACK`-after-commit/`XAUTOCLAIM`).** The hot path enqueues (returns 202 + a correlation id), a consumer drains at a controlled rate → DB. At-least-once; crash-safe (NOT bare `XREAD`, which loses messages on crash — the original blocker).
3. **Tier 3 — MariaDB conditional UPDATE (`WHERE stock >= qty`).** The final backstop. The consumer claims stock FIRST (conditional UPDATE), inserts the order only if the claim succeeded; on FAILED it re-credits stock + `DEL`s the one-per-user key.
4. **Idempotency:** `orders.idempotency_key` UNIQUE = the sole authority (DB constraint = atomic check-and-insert; Redis `SET NX` only as an optional pre-check, never sole). Fixes the check-Redis-then-insert TOCTOU.
5. **One-per-user:** `seckill:{pid}:{uid}` TTL set AFTER durable persist (not in the hot path); `DEL`'d on FAILED/CANCELLED so retry works.

**The order-id model:** the client polls a *correlation id* (an `AtomicLong` seeded from `millis*1000 + SecureRandom.nextInt(1M)`, hardened in Phase F against cross-restart collision); the DB PK is separate (AUTO_INCREMENT). `order_id_map:{corrId}` → realPk in Redis bridges them.

### 2d. The auth + security model
- **Stateless JWT** (HS256), claims: sub(userId), username, role. `JwtAuthenticationFilter` reads `Authorization: Bearer ...` on every HTTP request, populates `SecurityContext`. No server-side session → horizontal scaling works.
- **URL-matcher role rules** (not method security): `/admin/**` → `hasRole("ADMIN")`, public reads permitAll, everything else authenticated.
- **WebSocket auth:** JWT passed in the STOMP CONNECT frame's `Authorization` header (the browser WS API can't set headers), read by `AuthChannelInterceptor` → stamps a `SecurityUser` principal. A `SUBSCRIBE` ownership check closes the delivery-fan-out IDOR (a user can't subscribe to another user's `/topic/session/{id}`).
- **Operation logging (NFR):** an AOP `@Around` aspect writes `operation_log` for every POST/PUT/DELETE (params serialized, secrets masked, cost_ms, IP).
- **防SQL注入:** all SQL is MyBatis-Plus `#{}`-parameterized; the only `${}` is `sortOrderSql`, a service-derived constant (never raw user input).
- **Account lockout (UC018):** `INCR login:fail:{userId}` (30-min sliding TTL); at 10 → `status='LOCKED'`; forgot-password via SMS unlocks + clears.

### 2e. The coins economy (UC012)
- 100 coins = ¥1 = 100 cents. `paidInCents = totalInCents - coinAmount` (1:1).
- Three idempotent earn paths: daily-login (100/day, 4am CST refresh, `uk_coin_daily`), review-reward (= order's actual paid amount in yuan, `uk_coin_idem "review:{orderId}"`), refund-return (restores the original per-credit expiry snapshotted on the spend row's `ref_credit_expires_at`).
- FIFO spend at checkout (`SELECT...FOR UPDATE` oldest-first, nullifies consumed credits' `expires_at`). 50%-of-undiscounted-subtotal cap, 100-coin granularity, flash-sale items reject coins.
- 30-day per-credit expiry sweep (`@Scheduled` 4:01am CST).

### 2f. The live chat (UC011)
- STOMP over SockJS. Per-user sessions + admin inbox. Admin auto-assign to an online admin (round-robin over `AdminPresenceRegistry`); if none online, the message persists and surfaces when an admin connects. Persist-before-publish (every message written to `chat_message` before `convertAndSend`).

---

## 3. The hardest tasks we walked through

### 3a. The flash-sale concurrency model (the original MVP, pre-phase-A)
The single hardest design problem. Bare `XREAD` lost messages on crash; check-Redis-then-insert had a TOCTOU race; the one-per-user key set in the hot path locked users out if the consumer crashed after the key-set but before persisting. The solution — Lua gate + Stream consumer-group + DB conditional UPDATE + idempotency-key-as-authority + one-per-user-key-after-persist — took the most iteration. Every later phase built on this being correct.

### 3b. Phase E — the coin ledger state-flag model + three masked Critical bugs
The coins economy had a subtle design: `expires_at` doubles as a state flag (set = pending, NULL = spent/expired/terminal), balance = `SUM(amount) WHERE expires_at IS NULL OR > now()`. The final whole-branch review caught **two Critical bugs the per-task reviews + integration tests masked**:
1. **Refunded coins permanently unspendable:** `refundCoins` restored `originalCredit.getExpiresAt()`, but `spendAtCheckout` had nullified it → the refund restored null → REFUND_RETURN rows inflated the balance but could never be re-spent. (The unit test masked it with a mocked future-expiry credit.) **Fix:** a new `ref_credit_expires_at` column snapshots the credit's expiry on the spend row before nullifying; the refund restores from there.
2. **Consumer coin-failure catch in a rollback-only tx:** `spendAtCheckout` is `@Transactional REQUIRED` (joins the consumer tx); on `BusinessException(CONFLICT)` throw, Spring marked the shared tx rollback-only BEFORE the catch → the FAILED write + Redis recredit were discarded, the RPOP'd payload lost. **Fix:** a `canSpend` pre-check (a read, no tx marking) before calling `spendAtCheckout`, so the common insufficient case never hits the rollback-only path.

### 3c. Phase F — the chat IDOR class (three surfaces)
The IDOR (insecure direct object reference) on chat was a cross-cutting security hole that emerged only in the final review of Task 6:
1. **REST history** — `GET /chat/sessions/{id}/messages` took no `userId`, no ownership check → any USER could read any session by enumerating IDs.
2. **STOMP SUBSCRIBE** — any authenticated client could `SUBSCRIBE /topic/session/{sessionId}` for a session they don't own and receive broadcasts.
3. **STOMP SEND** — (this one was already ownership-checked, but only by luck of the controller branch).

Closing it across all three required: a `historyForUser` + `loadAndCheckOwnership` for the REST path; a `SUBSCRIBE` branch in `AuthChannelInterceptor` (parse sessionId from the destination, load the session, verify ownership, ADMIN bypass) for the delivery path. Plus a non-atomic find-or-create race (two concurrent opens → two OPEN sessions) fixed with a virtual-column unique index. Plus a `chat_message.update_time` runtime crash (the entity inherited `updateTime` filled by the audit handler, but the table lacked the column → first real insert threw `Unknown column`). The Task 7 STOMP integration test then surfaced four Spring Boot 4.1 subtleties (raw-WS rejects against SockJS endpoints → test-only non-SockJS endpoint; the test endpoint needed its own `@Order(0)` security chain; the `MappingJackson2MessageConverter` uses Jackson 2 but `LocalDateTime` needs the JavaTimeModule via `findAndRegisterModules()`; the `getPayloadType` override). This was the most fix-loops-per-task phase.

### 3d. Phase F — the e2e SMS-cascade (13 failures)
The e2e harness's `POST /auth/register` was still the old 2-arg `{username, password}` contract, but the backend (Phase A) now requires 5-arg `{username, password, phone, code, agreed}` (SMS-gated). Register 500'd → cascaded into products/sale/checkout/concurrency (they needed a real user token → 401s). The fix (`registerWithCode` helper, redis-cli pre-seed of `sms:code:REGISTER:{phone}` + 5-arg) **surfaced three masked pre-existing bugs** once the cascade cleared: missing `customerPhone`/`paymentMethod` in checkout payloads (previously masked by the 401), a per-user rate-limit bucket overflow (forced-admin principal fix), and the Windows `execSync`-vs-`execFileSync` path-resolution issue. The lesson: a failing test can hide unrelated bugs behind its own failure.

### 3e. The 10k QPS load proof — an honest bottleneck
The list cache was straightforward (read-through Redis, normalized key, SET-based invalidation). The load *proof* turned into an exercise in honesty: the Node `fetch`-based load client physically cannot drive 10k req/s from one process (`setInterval` dispatch ceiling + undici connection pool), even though the server serves cached reads at p95=7ms. The deliverable became a **documented bottleneck** (the cache works; the client can't prove the ceiling) + the two-host scaling path — which is exactly what the spec's acceptance bar allowed ("if the single host falls short, document the bottleneck and the distributed variant"). A dedicated tool (k6/autocannon) would be needed to prove the server's true ceiling.

---

## 4. What's left / hasn't been done

### 4a. Two manual steps (need the user's hands — runbooks in `docs/superpowers/PHASE-F-STATUS.md`)
1. **Aliyun SMS activation.** Create the 短信认证服务 instance, get the access keys, wire the confirmed SDK calls in `AliyunVerificationProvider` (currently a fails-closed stub), verify one real SMS send. The dev `LoggingVerificationProvider` stays the working default until then.
2. **VM redeploy.** Rebuild the jar, scp to the VirtualBox VM, apply the chat DDL to the VM's MariaDB, restart the backend, rebuild the frontend. Surfaces UC010 (reviews) + UC011 (chat) + UC012 (coins) on the live VM (which still runs stale Phase-B/C code).

### 4b. The 10k QPS server-ceiling proof
The cache is built + correctness-tested, but the single-Node load client can't generate 10k req/s to prove the server's actual ceiling. A dedicated load tool (k6/autocannon) is needed for the genuine 10k proof. (Documented as a deferred item, not a regression.)

### 4c. OAuth2.0 (explicitly deferred)
The use cases' "安全性: 采用OAuth2.0认证机制" NFR is unmet in this version (standing decision to save time; existing JWT retained). Noted in the NFR matrix as "deferred."

### 4d. The two-host distributed deployment
Still the next major milestone. Stateless JWT + shared Redis/MariaDB makes load-balancing "just work" — a second VM with the backend jar pointing at the shared Redis/MariaDB on VM1, a load-balancer in front. This is the documented scaling path for the 10k QPS NFR.

### 4e. The cosmetic deferred-minors backlog (in `docs/superpowers/skipped-issues.md`)
A long tail of non-blocking items from phases A-F: dead imports, missing `CoinMapper.xml` resultMap, the `CoinDiscountPreview` VO suffix, coins-page first-50 pagination (no UI controls), the stale ORDER_SEQ test comment, `Math.abs(Long.MIN_VALUE)` overflow in `nextOnlineAdmin` (astronomically unreachable), the chat `sendAdminMessage` lacking a service-level ADMIN re-check (defense-in-depth, controller branch is the guard), the e2e ownership test tolerating 404 alongside 403 (loose assertion), the `ChatIntegrationTest` 200ms subscribe sleep (loud-failure mode), `loadAndCheckOwnership` throwing FORBIDDEN vs the `OrderServiceImpl` NOT_FOUND convention. None block anything; triage as time allows.

### 4f. A few real-payment / captcha gaps (by spec scope decision)
Real payment gateway (mock 默认成功 per the use cases), real captcha (in-house SVG used, not third-party), real SMS tests (provider verified manually, not in the automated suite).

---

## 5. The build methodology that got us here

Every phase used **superpowers subagent-driven-development**: a design spec (brainstorming skill) → an implementation plan (writing-plans skill) → a per-task implementer subagent + per-task code review + fix loop → a final whole-branch opus review → merge to main → re-run the full test suite as a regression gate. The fresh-subagent-per-task + per-task review + final cross-cutting review pattern caught several test-masked Critical/Important bugs (the Phase E coin bugs, the Phase F IDOR class) that the per-task integration tests didn't. The ledger (`.superpowers/sdd/.../progress.md`) was the recovery map across context compactions and transient API errors (two Task 6/7 agents died on stream-idle timeouts; the ledger + git history let us resume cleanly). Auto mode (decide alone, log+skip critical items) was granted early and held throughout.
