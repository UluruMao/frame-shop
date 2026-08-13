# Design — Frame Shop (flash-sale storefront on the Spring Boot template)

> Date: 2026-08-12. Built on top of the existing `frame-backend` + `frame-frontend` template.
> Concurrency model follows Alibaba/Taobao seckill (秒杀) architecture: defense in depth, Redis as the primary stock gate, DB conditional update as the final source of truth.
> This is the "Mini Shop" e-commerce demo (previous project at `C:\Users\tcmao\Desktop\ecommerceProject`) rebuilt on the production stack, with the concurrency/queue/distributed pieces that project deferred now actually implemented.

## 1. What we're building

A flash-sale storefront. Limited-quantity products; many users buy a scarce item at once. The defining characteristic that justifies the machinery: a burst of concurrent buyers for an item with small stock, where overselling must be impossible and the DB must not be the bottleneck.

Built **into** the existing template, not a new repo:
- Keep: JWT auth, `Result<T>` wrapper, `GlobalExceptionHandler`, MyBatis-Plus, SpringDoc, CORS, Vite proxy, Zustand auth store, Axios `request` instance, Tailwind, recharts.
- Transform: the `DemoProduct` entity → real `Product`; the commented Redis block in `application.yml` → live; the commented `decrementStock` SQL → real mapper method (the DB backstop).
- Add: `User`/`Order`/`OrderItem` entities; Redis Lua stock gate; Redis Stream order buffer + consumer; one-per-user + idempotency keys; image upload endpoint.

## 2. Architecture — the three tiers (Taobao-style defense in depth)

```
Tier 1 — Edge (Vite/Nginx in dev; Nginx in prod)              "shed early"
  static assets served without hitting backend; (prod: Nginx rate limit)

Tier 2 — Application (Spring Boot :8081) + Redis              "the real gate"
  - per-user limit:  Redis key  seckill:{productId}:{userId}  → one purchase per user
  - rate limit:      Redis token bucket on /checkout
  - stock claim:     Redis Lua script — atomic check-and-decrement (shields DB from burst)

Tier 3 — Database (MariaDB)                                   "final source of truth"
  - Redis Stream carries the reserved order to a consumer
  - consumer: conditional UPDATE (where stock>=qty) + insert orders/order_item, @Transactional
```

Philosophy (copied from Alibaba): shed traffic as early as possible; the DB is the last line of defense, never the first.

## 2a. Template capability gap — role-based authorization (must build, not reuse)

The template's security layer **cannot express `role=ADMIN`** as-is; build step 2 must add it:
- `JwtUtil.generateToken` puts only a `username` claim today — **add a `role` claim**.
- `SecurityUser.from` hardcodes `List.of(new Authority("ROLE_USER"))` for every token — **read the role from claims** (ADMIN→`ROLE_ADMIN`).
- `SecurityConfig.filterChain` has only `permitAll` vs `anyRequest().authenticated()` (no `hasRole` anywhere; `@EnableMethodSecurity` not enabled) — **add `requestMatchers(POST,"/products").hasRole("ADMIN")`** + PUT/DELETE/image/admin-sales equivalents. As written today, admin endpoints are wide open to *any* authenticated user.
- No `UserDetailsService` exists (`AuthServiceImpl` is a hardcoded `admin/admin123` stub) — **add a `UserDetailsService` bean backed by the `user` table** (this replaces the stub).

This is called out explicitly because the spec's auth assumptions ("admin endpoints require role=ADMIN") would otherwise read as if the template already supports it.

## 3. Data model (ported + extended from the old project)

Money is integer cents everywhere (never floats — carried over from old project).

- **`user`** (NEW — old project had none): `id`, `username`, `password_hash` (BCrypt), `role` (USER/ADMIN), audit cols. Backs Spring Security `UserDetailsService`. Replaces old shared-secret admin token. (See §2a — the template's JWT/SecurityConfig cannot express roles yet; this is work, not reuse.)
- **`product`** (from `DemoProduct`, renamed): `id`, `name`, `description`, `price_in_cents` (int), `stock` (int), `image_url`, `category`, `is_flash_sale` (bool, NEW), `flash_sale_at` (datetime, NEW — stored but **not time-gated in V1**; a sale "opens" when an admin loads `stock:{id}` into Redis via the admin sale endpoint (§5), not when the clock hits `flash_sale_at`). audit cols. `@TableName("product")`.
- **`orders`** (ported + extended): `id`, `user_id` (NEW — was anonymous), `customer_name`, `customer_email`, `shipping_address`, `total_in_cents`, `status` (enum `PENDING`/`PAID`/`CANCELLED`/`FAILED` — was free-form string, now a MyBatis-Plus enum; `SHIPPED` deferred to §11, not in the V1 enum to avoid dead values), **`idempotency_key` (`VARCHAR(64) NOT NULL`, `UNIQUE KEY uk_idempotency_key`)** — the atomic check-and-insert that enforces no-double-order (see §4 dedup). audit cols. `@TableName("orders")` (reserved word in SQL).
- **`order_item`** (ported): `id`, `order_id`, `product_id`, `name` (snapshot), `price_in_cents` (snapshot), `quantity`. Snapshot rule preserved — order history immutable to later price/stock changes.

No `reserved_stock` column (conditional `where stock>=qty` makes it unnecessary — same as old project). No separate `payment` table (payment is the atomic `pending→paid` flip on `orders.status`, per old project + mentor's "mock returns success" instruction).

Each entity extends `BaseEntity` (id + createTime + updateTime auto-fill); each mapper extends `BaseMapper`; services follow the `DemoProductService` interface+impl pattern.

## 4. The stock gate — two layers, Redis-then-DB (the core design decision)

Redis is the primary gate (fast, shields the DB); the DB conditional update is the final reconciliation/backstop. **At-least-once delivery and no-double-order are enforced by constructed mechanisms, not asserted as properties** (see §4a for the full at-least-once/idempotency construction).

**At sale setup** (admin action via `POST /admin/sales/{productId}`, §5): atomically load `stock:{productId}` from the DB into Redis. Because this load *is* what opens the sale (§3), no checkouts are in flight yet — Redis and DB are guaranteed in sync at this moment. (If a Redis restart/flush drops `stock:{pid}` mid-sale, recovery re-loads from *current DB stock*, not the original quantity — see §4b.)

**At checkout**, the flash-sale hot path:
1. **Redis Lua script** (atomic, single-threaded in Redis) — **two-pass, all-or-nothing** for multi-item carts: pass 1 checks *every* item's `stock:{pid} >= qty` (collecting keys); only if ALL pass, pass 2 `decrby`s all collected keys; if any check fails, return `FAIL` with **no mutations performed** (a partial shortfall leaves all Redis stock keys unchanged). The literal script must use `tonumber()` on the `get` result (a missing key → nil → treat as 0 → FAIL, which is fail-closed-against-oversell):
   ```
   -- KEYS = stock:{pid}... ; ARGV = qty,qty,...  (paired)
   local to_decr = {}
   for i = 1, #KEYS do
     local s = tonumber(redis.call('get', KEYS[i]))
     local q = tonumber(ARGV[i])
     if not s or s < q then return 'FAIL' end
     to_decr[i] = {KEYS[i], q}
   end
   for _, pair in ipairs(to_decr) do redis.call('decrby', pair[1], pair[2]) end
   return 'OK'
   ```
   This gate rejects ~all burst traffic instantly, never touching MariaDB.
2. If Redis says OK → `XADD` the order message (carrying `userId`, `idempotency_key`, items, `totalInCents`) to the **Redis Stream** (`orders:pending`), return `202 Accepted { orderId, status: "queued" }` immediately. (The 缓冲/buffer — decouples burst from DB write rate.) **Do NOT set the one-per-user key here** — it is set by the consumer only after the order durably persists (§4a), so a consumer crash never locks the user out (fixes the crash-lockout blocker).
3. **Consumer** drains the stream via a **consumer group** (§4a) and does the durable write in one `@Transactional` block (claim stock FIRST, then insert the order only if the claim succeeded — full ordering in §6 step 7):
   - conditional `UPDATE product SET stock = stock - #{qty} WHERE id = #{id} AND stock >= #{qty}` (the DB backstop — same pattern as the old project, now the second layer).
   - if rows = 0 (Redis drifted) → insert the order as `FAILED` (visible via GET /orders/me), **`DEL` the one-per-user key** (so the user can retry), **re-credit Redis** (`incrby` stock back), log WARN, `XACK` (terminal). Stock integrity guaranteed.
   - else insert `orders` (status=PENDING) + `order_item` (snapshots); a duplicate redelivery that re-runs the UPDATE+insert violates `uk_idempotency_key` → caught → treat as already-processed (do NOT re-decrement; the prior delivery already claimed the stock), `XACK` + skip. Then set the one-per-user key `seckill:{pid}:{uid}` (TTL = sale duration) inside the same commit boundary — so the key reflects a *persisted* order, not a transient Redis claim. **`XACK` the stream message only AFTER the `@Transactional` commits** (never before) so a crash before commit leaves the message in the pending-entries-list (PEL) for redelivery (§4a).

### 4a. At-least-once + no-double-order — the constructed mechanism (fixes the assertion-only blocker)

The hot path asserts at-least-once and no-double-order; this is how they are actually produced (do NOT rely on bare `XREAD` — that loses messages on consumer crash):

- **Consumer group**: on startup, `XGROUP CREATE orders:pending order-consumers $ MKSTREAM` (use `0` if messages produced before group creation must also be processed). The group holds a pending-entries-list (PEL) of messages that have been delivered but not yet acked.
- **Read**: `XREADGROUP GROUP order-consumers <consumer-name> COUNT n BLOCK <ms> STREAMS orders:pending >` — delivered messages enter the PEL, so a consumer crash after read but before commit does NOT lose them.
- **Ack**: `XACK orders:pending order-consumers <id>` is issued **only after the `@Transactional` write commits** (success or idempotency-skip). On the FAILED branch we still `XACK` (the order is durably FAILED + re-credited + key-del'd; redelivering would just re-run the idempotency-skip, but acking keeps the PEL clean). This is the at-least-once guarantee: a crash before commit → message stays in PEL → redelivered.
- **Stalled-consumer recovery**: a `@Scheduled` task runs `XPENDING orders:pending order-consumers` to find entries idle longer than N (e.g. 60s), `XAUTOCLAIM`s (or `XCLAIM`s) them to a healthy consumer for reprocessing. Because the idempotency-key DB unique constraint gates the re-insert, a duplicate that already committed is safely rejected — no double order.
- **Stream trimming**: `XADD ... MAXLEN ~ N` (or periodic `XTRIM`) keeps `orders:pending` bounded.
- **No-double-order authority**: the `orders.idempotency_key` DB unique constraint is the *sole* authority. Redis may be used as a fast pre-check (`SET idemp:{key} NX`) but must never be the sole authority (check-Redis-then-insert-DB is a TOCTOU).

### 4b. Redis recovery (missing stock key mid-sale)

If `stock:{pid}` is absent for a live `is_flash_sale` product (Redis restart/flush mid-sale — the Windows port defaults to no AOF), checkout returns **503 "stock not initialized"** rather than treating nil as "sold out" (so the sale is fail-closed against oversell *and* doesn't falsely report sold-out with DB stock remaining). Recovery: on boot or on detecting the missing key, the service re-loads `stock:{pid}` from *current DB stock* (`SELECT stock FROM product WHERE id=?`) — never the original sale quantity (which could drift Redis > DB and cause mass FAILED orders). Optionally enable AOF (`appendfsync everysec`) for the sale window as belt-and-suspenders.

### Why Redis as the primary gate (not just the DB conditional update)
The old project used the DB conditional update alone — correct, but every concurrent buyer hits MariaDB directly (bottleneck/deadlock risk under real load). Taobao's insight: move the hot-path check-decrement to Redis (microseconds, no contention via single-threaded Lua), let the DB see only the small controlled stream the consumer feeds it. Same correctness guarantee (DB conditional update still there as backstop), far higher throughput.

### Deduplication (Taobao's one-per-user + idempotency)
- **One per user**: `seckill:{productId}:{userId}` key — but set **after the order durably persists** (§4 step 3), not in the hot path. Second attempt (by a user with a persisted order) finds the key, rejected (409 "already purchased"). A user whose order FAILED has the key `DEL`'d (§4 step 3), so retry is possible — matching §8's "please retry" instruction.
- **Idempotency**: each checkout carries a client-generated idempotency key; enforced by the `orders.idempotency_key` DB unique constraint (§4a) — at-least-once Stream redelivery cannot produce a duplicate order.

## 5. Endpoints

Auth/authorization: `/auth/**` public; the rest require JWT; admin endpoints require `role=ADMIN`. **⚠ The template cannot express `role=ADMIN` yet** — its JWT carries no role claim, `SecurityUser.from` hardcodes `ROLE_USER`, `SecurityConfig` has *no* role rules (only permitAll vs authenticated, so admin endpoints are currently wide open to any logged-in user), and there's no `UserDetailsService` (auth is a hardcoded stub). Build step 2 must add: a role claim in `JwtUtil.generateToken`; read it in `SecurityUser.from` (remove the hardcode); `hasRole("ADMIN")` rules in `SecurityConfig`; a `UserDetailsService` bean backed by the `user` table. (See §2a.)

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/auth/register` | public | create user (role=USER) |
| POST | `/auth/login` | public | template login, now backed by real `user` table |
| GET | `/products` | public | list (filter: `flashSaleOnly`, `category`); Redis-cached (short TTL + invalidate on PUT/DELETE, §8) |
| GET | `/products/{id}` | public | detail; Redis-cached (same TTL/invalidate) |
| POST | `/products` | ADMIN | create (incl. image upload) |
| PUT | `/products/{id}` | ADMIN | update — **must resync `stock:{id}` in Redis if the key exists** (active sale), else stale gate (§8) |
| DELETE | `/products/{id}` | ADMIN | delete |
| POST | `/products/{id}/image` | ADMIN | upload image → returns `imageUrl` |
| **POST** | **`/admin/sales/{productId}`** | **ADMIN** | **open a flash sale**: atomically load `stock:{id}` from DB into Redis (this is the sale-setup action §4 refers to); mark sale live |
| POST | `/checkout` | USER | flash-sale hot path (see §6 sequence). Returns `202 {orderId, status:"queued"}` |
| GET | `/orders/{id}` | USER | **status endpoint** — returns `queued` (via `order_status:{id}` Redis key set at enqueue) / `pending` / `paid` / `failed` / `cancelled`. Closes the gap where a queued order has no row yet (§6 step 8). |
| POST | `/orders/{id}/pay` | USER | mock payment: atomic `pending→paid` flip |
| POST | `/orders/{id}/cancel` | USER | `pending→cancelled` (atomic guard `WHERE status='pending'`); `DEL` one-per-user key so the user can re-buy |
| GET | `/orders/me` | USER | my orders (includes FAILED + CANCELLED so the user sees their failed orders and can retry) |

Cart stays client-side (React context + localStorage), ported from old project. Product reads are public (browsing shouldn't require login — same as old project). Rate-limiter applies to `/checkout` only.

## 6. Flash-sale checkout sequence (the heart of the design)

```
1. Client POST /checkout  { items:[{productId,quantity}], idempotencyKey }
   (cart recomputed server-side, prices never trusted from client — ported rule)
2. Rate-limit filter: Redis token bucket per user → 429 if exceeded
3. For each item: check one-per-user key  seckill:{productId}:{userId}
   exists → 409 "already purchased this item"  (key is set only AFTER persist, §4 step 3,
           so a queued/failed order does NOT block retry — this check is for completed orders only)
4. Redis Lua script (atomic, two-pass all-or-nothing — §4 step 1):
   check ALL items stock:{pid} >= qty; only if all pass, decrby all; else FAIL (no mutations)
   FAIL → 409 "sold out" (DB NEVER touched by the burst). Missing stock:{pid} → 503 (§4b).
5. All items claimed in Redis OK →
   XADD order msg to Redis Stream  orders:pending  (carries userId, idempotency_key, items, total)
   set order_status:{orderId} = "queued" in Redis  (so GET /orders/{id} can report queued before persist)
   return 202 Accepted { orderId, status: "queued" }
   ──── (request returns; user sees "processing") ────
   NOTE: one-per-user key is NOT set here — set by consumer only after durable persist (step 7).
6. Consumer (consumer group, §4a) reads msg via XREADGROUP (enters PEL — crash doesn't lose it)
7. @Transactional (order of operations: claim stock FIRST, then insert the order only if the claim succeeded):
   a. conditional UPDATE product SET stock=stock-qty WHERE id=? AND stock>=qty  (DB backstop)
      rows=0 (Redis drifted) → mark order FAILED (insert orders row with status=FAILED + idempotency_key,
              so the failed order is visible via GET /orders/me), DEL one-per-user key if set,
              re-credit Redis (incrby), log WARN, then XACK (terminal FAILED — no retry of this message)
   b. on rows>=1: insert orders(status=PENDING, idempotency_key=...) + order_item (snapshots)
      — duplicate redelivery: the orders insert violates uk_idempotency_key → caught
        (DataIntegrityViolationException) → the stock UPDATE already ran on the prior delivery,
         so we must NOT re-decrement; treat as already-processed, XACK + skip (no double order, no double decrement)
   c. set one-per-user key seckill:{pid}:{uid} (TTL=sale duration) — inside this commit boundary
   d. XACK the stream message ONLY AFTER commit (never before) — crash before commit → msg stays in PEL → redelivered
8. Order PENDING (or FAILED); user polls GET /orders/{id} → status transitions queued→pending (pay) / queued→failed (retry)
```

Payment (`POST /orders/{id}/pay`): atomic `UPDATE orders SET status='paid' WHERE id=? AND status='pending'`, 0 rows = "already paid"/"can't pay". Ported verbatim from old project's `updateMany`. Mock returns success per mentor's instruction.

## 7. Frontend (ported + extended from old project's React app)

Ported onto the template's existing React 18 + Vite + Tailwind + Router + Zustand + Axios stack:
- `ProductList`, `ProductDetail`, `CartPage` (React context + localStorage reducer), `AdminPage` (product CRUD + **"open flash sale" action** calling `POST /admin/sales/{productId}`).
- `LoginPage`/`RegisterPage` (replaces old shared-secret admin token with the template's JWT — the Zustand auth store already exists); flash-sale badge + countdown on `ProductList`; recharts "stock remaining" bar on the flash-sale view.
- **`CheckoutPage` is a THREE-stage flow, not the old project's two-step synchronous one** (the async design changes this): **queued/processing** (`POST /checkout` returned `202 {orderId, status:"queued"}`; order not yet persisted — poll `GET /orders/{id}` until status leaves "queued") → **pending/ready-to-pay** (consumer persisted the order; fetch its details, render the pay UI — note: the old project's 'paying' stage had the order object in hand synchronously from `createOrder`; here `/checkout` returns only `{orderId, status}`, so the order must be fetched via the poll before the pay UI can mount) → **paid** (`POST /orders/{id}/pay` succeeds). Cart is cleared only after **PAID** (later than the old synchronous flow due to the queued→pending window — acceptable; a user abandoning between place and pay leaves a PENDING order they can cancel via `POST /orders/{id}/cancel`).
- Template reuse: the Axios `request` instance (token attach, `Result` unwrap, 401→login) is exactly what this needs — no new HTTP layer. (Fix the stale `:8080` comment in `request.ts` → `:8081` during this step.)

## 8. Error handling (extends template's GlobalExceptionHandler + Result<T>)

- **Extend `ResultCode`**: add `CONFLICT(409, "conflict")` and `TOO_MANY_REQUESTS(429, "too many requests")` (503 already exists as `SERVICE_UNAVAILABLE`). **Extend `GlobalExceptionHandler.handleBusiness`** so the HTTP status reflects the carried code (409→CONFLICT, 429→TOO_MANY_REQUESTS, 503→SERVICE_UNAVAILABLE, default→BAD_REQUEST) — *today it returns 400 for every BusinessException, which would break the §6 contract.* Either a code→HttpStatus lookup, or dedicated subclasses (`ConflictException`, `RateLimitException`, `ServiceUnavailableException`) each with its own `@ExceptionHandler`. Body still carries `Result.fail(code, message)`. Frontend `request.ts` error interceptor branches on HTTP status (409/429/503) if distinct UX is wanted (today it only special-cases 401).
- Checkout failures return the right code: 429 (rate-limited), 409 (sold out / already-purchased / already-paid), 503 (Redis outage / stock-not-initialized per §4b), 400 (validation / insufficient stock from DB backstop), 500 (unexpected).
- Never leak internals — "sold out"/"already purchased" are generic to the client; server log carries the real reason. (Old project's rule, carried over.)
- Fail-closed on Redis outage: checkout returns 503, never falls back to a direct DB write that bypasses the gate. Overselling impossible because the gate refuses to open. (And a missing `stock:{pid}` mid-sale → 503 "not initialized" + recovery re-load, §4b — not a false "sold out".)
- Reconciliation failures (Redis/DB drift) → order `FAILED`, one-per-user key `DEL`'d (so retry works), Redis stock re-credited, logged WARN. Surfaced to user as "order failed, please retry" — never silent.
- Stream consumer at-least-once (consumer group + XACK-after-commit, §4a) + `idempotency_key` DB unique constraint → duplicate redelivery causes no double order.
- **Product read-cache contract** (§5 GET /products): short TTL (e.g. 30s) + invalidate on `PUT`/`DELETE /products/{id}` (delete/re-populate the cached entry) so an admin stock/price update isn't served stale during a sale.
- **Redis resync on admin product edit**: `PUT /products/{id}` must resync `stock:{id}` in Redis if the key currently exists (active sale), else the gate sells from a stale count. (Alternatively reject stock edits during an active sale — pick one; default: resync.)

## 9. Testing (the old project had none — the port fixes that)

- **Backend unit** (JUnit + Mockito): `CheckoutService` logic — mock Redis (Lua OK/FAIL/partial), mock mapper (conditional update 1/0 rows), assert right `Result`/exception per branch. `PaymentService` atomic flip (0 rows → "already paid"). The `StreamConsumer` — mock `XREADGROUP`/`XACK`, verify XACK happens only after commit, verify idempotency-skip on duplicate. These race-guard methods get direct tests.
- **Integration**: `@SpringBootTest` + embedded Redis (e.g. `com.github.codemonstur:embedded-redis` — no Docker needed) + MariaDB. Drive a checkout through the real Redis → Stream → consumer → DB path; assert order created + stock decremented + one-per-user key set. Add a **consumer-crash test**: kill the consumer mid-processing, assert the message is redelivered (PEL) and the order still completes (or FAILED + re-credit fires) — closes the at-least-once loss window.
- **Load/mutex test** (the money test for the mentor) — *concretely specified*: spawn **M=50 threads (Java `ExecutorService`/`CountDownLatch`, or k6), each a DISTINCT registered user**, each `POST /checkout` for 1 unit of a product with `stock=K=5` (K<M). Assert: exactly K return 202 and, after the consumer drains, exactly K orders reach `status=PENDING`, (M−K) return 409 with the "sold out" code (NOT "already purchased" — that requires distinct users, which is why), final DB stock=0 AND Redis `stock:{pid}`=0 (they can diverge after a drift re-credit). Separate one-per-user test: same user, two sequential checkouts for the same product → first 202 (or 409 sold-out if stock exhausted), second 409 "already purchased". The artifact you show the mentor.
- **Frontend** (Vitest): cart reducer logic in isolation (pure function — the test the old project never had); `CheckoutPage` three-stage flow with mocked API (queued→pending→paid).

## 10. What this demonstrates (mapped to mentor's topics)

Rows marked with §11a/§12 are demonstrated **only after the corresponding build phase executes**; at design time they are planned, not yet demonstrable.

| Mentor topic | Where it's demonstrated |
|---|---|
| 前后端分离 | React SPA ↔ Spring Boot over JSON (template) |
| 分布式部署 (incl. Linux deploy) | stateless JWT + shared Redis → any instance serves any request; **will be deployed to a Linux server behind Nginx as build step 9 (§11a), planned after V1 works locally** — design decisions made now (externalized config, portable paths, profile-based jar). The mentor's literal "FE/BE on separate servers behind a load balancer" (HANDOFF §6) is fully shown only if the §11a two-host variant is done (base V1 deploy demonstrates front-back separation by process/Nginx on one host). |
| 高并发/缓冲 | Redis Lua gate sheds burst; Redis Stream decouples ingest from DB write rate; Redis-cached reads in front of DB (§8) |
| 并发与互斥 | Lua script (atomic Redis mutex, two-pass) + DB conditional `UPDATE ... WHERE stock>=qty` (backstop) + consumer-group XACK-after-commit + `idempotency_key` unique constraint (§4a) |
| 数据库长期保存 | MariaDB (vs old SQLite) + HikariCP pool; DB is durable source of truth |
| (HANDOFF task #4) middleware comparison | §11d contrasts Redis (fast/in-memory/ephemeral gate+cache) vs MariaDB (durable) vs Redis Stream vs Kafka/RabbitMQ (when a real MQ is warranted) |

### 11d. Middleware choices (the comparison artifact the mentor asked for — HANDOFF task #4)
- **Redis** — fast, in-memory, ephemeral; the stock gate (Lua), the read cache, and the Stream buffer. Right when microsecond latency and shedding burst matter; wrong as a durable source of truth (volatile → §4b recovery).
- **MariaDB** — durable source of truth (orders, products, stock reconciliation), HikariCP pool; the DB conditional UPDATE is the backstop the Redis gate feeds. Right for persistence; wrong as the hot-path gate under burst.
- **Redis Stream vs Kafka/RabbitMQ** — Stream chosen for V1 because Redis is already a hard dependency (no second broker to operate) and Stream gives at-least-once + consumer groups, sufficient for the single-service flash-sale buffer. A real MQ (Kafka/RabbitMQ) is warranted when: cross-service durability/fan-out, multiple independent consumer teams, replay across services, or the buffer must outlive the Redis process — then Stream is replaced with Kafka/RabbitMQ at the producer seam (§6 step 5), the rest of the design unchanged.

## 11. Deferred (not in V1)

Real payment gateway (Stripe SDK at the `setTimeout` seam); `SHIPPED` status + the `shipped`/`cancelled` admin order-management UI (the `orders.status` enum keeps `PENDING`/`PAID`/`CANCELLED`/`FAILED` in V1; `SHIPPED` re-added when this lands); the Lua gate is built directly in V1 (not phased). Light Cones/Relics (n/a — wiki idea dropped).

**V1 order cleanup policy**: `FAILED` and `CANCELLED` orders are terminal and user-visible via `GET /orders/me`; a user retries by placing a *new* order (not reviving the FAILED row), and the one-per-user key is `DEL`'d on FAILED/CANCELLED so retry isn't blocked. A scheduled task expires stale `queued` orders (Stream messages undelivered beyond N minutes) via the consumer-group `XPENDING`/`XAUTOCLAIM` recovery (§4a) — no orphaned queued state.

## 11a. Linux deployment (mentor requirement — planned, do after V1 works)

The mentor's roadmap (HANDOFF.md §6, build task #2: "Write a small service + deploy to Linux") requires this project run on a Linux server. **This is a hard requirement, not optional.** It happens as the final phase, after the V1 build + tests pass locally on Windows. The build decisions below are made *now* so deployment is a smooth move, not a rewrite.

### Decisions affecting the build now (so we don't paint ourselves into a corner)
- **Config externalized to `application.yml`** — already true (template). Prod config via `--spring.profiles.active=prod` + an `application-prod.yml` (separate DB/Redis/JWT secrets), never secrets in the jar.
- **Build a deployable jar** — `mvn clean package -DskipTests` → `target/frame-backend-*.jar`. Runs with `java -jar` on any JDK 21, Linux or Windows. (Spring Boot's executable jar embeds Tomcat — no separate servlet container needed.)
- **Redis address not hardcoded** — backend reads Redis host/port from config; on Linux it points at the Linux Redis, on Windows at localhost. Already the case (template's `spring.data.redis.host`).
- **DB address not hardcoded** — same, via the datasource URL in the profile.
- **No Windows-specific code** — paths via `java.nio.file.Path` (not hardcoded `\`), image upload dir configurable (not `C:\...`). Flag this when implementing image upload (build step 3) so the upload dir is a config value, not a Windows path.
- **Frontend builds to static `dist/`** — `npm run build` → serve via Nginx on Linux (no Node runtime needed in prod).

### Deployment steps (the plan for the Linux phase)
1. **Provision a Linux server** — a VM (VirtualBox/Hyper-V locally, or a cloud small instance) running Ubuntu 22.04+ or similar.
2. **Install the runtime on Linux** (not the full dev toolchain — just what's needed to *run*): JDK 21 (headless), MariaDB server, Redis server, Nginx. (Maven not strictly needed on the server if you build the jar on Windows and copy it; or install Maven to build in place.)
3. **Build locally** (Windows): `mvn clean package` → jar; `npm run build` → `dist/`.
4. **Transfer**: copy the jar + `dist/` + `schema.sql` to the Linux server (scp/rsync).
5. **Database**: `mariadb < schema.sql` on the Linux MariaDB; set prod DB user/password.
6. **Backend**: `java -jar frame-backend-*.jar --spring.profiles.active=prod` — run as a `systemd` service (auto-restart, logs).
7. **Redis**: run as `systemd` service (default install does this).
8. **Frontend**: copy `dist/` to `/var/www/frame-frontend`; Nginx serves it static + reverse-proxies `/api/*` → `http://localhost:8081` (the same proxy pattern as Vite dev, now in Nginx). Nginx also = the Tier-1 edge (rate limit, gzip, static caching).
9. **Verify end-to-end on Linux**: register → login → flash-sale checkout → payment. Run the load/mutex test against the Linux server to prove the concurrency guarantees hold in the real deployment.
10. **Distributed (分布式) variant**: the base deployment (steps 1-9) demonstrates front-back separation by process/Nginx on one host. The mentor's literal "FE/BE on separate servers behind a load balancer" (HANDOFF §6) requires pointing Nginx's `/api/*` proxy at a backend on a *second host* (different IP/hostname) rather than `localhost:8081` — a one-line Nginx config change given the config is externalized (§11a decisions). The lighter "two backend instances behind Nginx" variant demonstrates horizontal scaling of the backend. Either is the real 分布式; stateless JWT + shared Redis/MariaDB makes load-balancing "just work."

### What this lets you demonstrate to the mentor
- Linux deployment (the explicit ask).
- Nginx as a reverse proxy + static edge (the Tier-1 "shed early" from the Taobao model).
- `systemd` for process management (how production services stay up).
- Profile-based config (same jar, dev vs prod env).
- (Optional) horizontal scaling behind a load balancer = the real 分布式.

This is tracked as the final build phase (§12 step 9) so it isn't forgotten.

## 11b. Infrastructure note — Redis installed

Redis is installed and running as a Windows service (port 6379), verified 2026-08-12:
- **Version**: Redis 5.0.14.1 (tporadowski/redis Windows port — the only maintained Windows build; upstream Redis is 7.x but has no Windows binaries). 5.0 has everything the spec needs: Lua scripting (since 2.6) and Streams (introduced in 5.0 — earliest version with them). For a learning template/demo this is fine; a production system would run Redis 7 on Linux.
- **Verified working**: `redis-cli ping` → PONG; Lua `eval` (set/get/decrby conditional) works; Stream `xadd`/`xlen`/`xread` works.
- **Bin on PATH**: `C:\Program Files\Redis` added to user PATH (fresh terminals resolve `redis-cli`).

**Pom change required** (not yet applied): add `spring-boot-starter-data-redis` to `frame-backend/pom.xml` — the commented `spring.data.redis.*` block in `application.yml` becomes active once this dependency is present. Redisson is NOT needed (the mutex is the Lua script + DB conditional update, not a Java-level distributed lock).

## 12. Build order (for the implementation plan)

1. Data model + schema (user, product, orders, order_item) + seed.
2. Real auth (register/login backed by `user` table) — replaces stub.
3. Product CRUD + image upload (admin) + Redis-cached reads. **Image upload dir must be a config value** (not a Windows path) — see §11a.
4. Cart (frontend, ported) + checkout endpoint with **synchronous** DB conditional-update gate + `idempotency_key` unique constraint first (get it correct; consumer-group/Stream comes in step 5).
5. Add Redis `spring-boot-starter-data-redis` to pom + the Redis Lua primary gate (two-pass) + Stream buffer + **consumer-group consumer** (`XGROUP`/`XREADGROUP`/`XACK`-after-commit/`XAUTOCLAIM`, §4a) + the `order_status:{id}` queued-state key. This is the Taobao model.
6. Payment (atomic flip) + `GET /orders/{id}` status endpoint + `POST /orders/{id}/cancel` + admin sale endpoint `POST /admin/sales/{productId}`.
7. Tests: unit (Checkout/Payment/StreamConsumer) → integration (incl. consumer-crash redelivery) → load/mutex test (M=50 distinct users, stock=5; §9).
8. Frontend: port pages (three-stage `CheckoutPage`, §7), login/register, flash-sale UI, admin "open sale" action. Fix the stale `:8080` comment.
9. **Linux deployment** (mentor requirement — see §11a): build jar + dist, provision Linux server, systemd + Nginx reverse proxy, verify end-to-end on Linux, run the load/mutex test against the deployed server. The §11a two-host variant (backend on a second box) is the full mentor-literal 分布式; two-instance backend scaling is the lighter variant.
