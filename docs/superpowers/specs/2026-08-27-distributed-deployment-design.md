# Distributed Deployment — Design Spec

> **STATUS (2026-08-27):** Brainstormed + approved. Scope = harden the single-instance backend for a multi-instance deployment behind a load balancer, then deploy to the company-provided cloud server (`172.29.231.145`) **without disturbing any existing data/services on it**. Sequenced **after** the feature round (`2026-08-27-feature-round-design.md`). The HTTP/REST + data plane is already multi-instance-ready (stateless JWT, shared Redis/MariaDB authorities); four real-time / async areas are NOT — this spec fixes them.
>
> **Two open items resolved by read-only recon at plan time** (see §6): (1) deployment topology, (2) shared-uploads decision. The recon itself is gated behind an SSH auto-mode guardrail — the user runs a read-only recon command via the `!` prefix; the output picks the branch.

## 1. Problem & Goal

`docs/superpowers/PHASE-F-STATUS.md` and `docs/superpowers/ARCHITECTURE-AND-RETROSPECTIVE.md` name the "two-host distributed variant" as the scaling path for the 10k QPS NFR. The recon found the backend is *partially* ready: the data plane scales today, but four areas break or silently lose data behind a load balancer. Additionally `ARCHITECTURE-AND-RETROSPECTIVE.md` *describes* the concurrency model as a Stream consumer group (XREADGROUP/XACK/XAUTOCLAIM), but the code reverted to a destructive `RPOP` List — a doc/code drift and a correctness gap.

**Goal:** Make every backend area safe under N≥2 app instances sharing one Redis + one MariaDB, then stand up the deployment on the cloud server, proving horizontal scalability without disturbing existing services there.

**Hard constraints (user, 2026-08-27):**
- Must NOT disturb any existing data/services on `172.29.231.145`.
- On port conflict, use another port (do not take an in-use port).
- No paid external services (no Aliyun OSS bill). MinIO (self-hosted, free) is acceptable if multi-host.
- Single free cloud server is the resource; everything runs on it (or it + the existing VirtualBox VM as a second host).

## 2. Locked Decisions

| Decision | Choice |
|---|---|
| Sequencing | After the feature round (features-first) |
| Orders queue | **Restore the Stream consumer group** — XGROUP/XREADGROUP with per-instance consumer names, XACK-after-commit, XAUTOCLAIM for dead-consumer redelivery (fixes doc/code drift; makes order processing horizontal + crash-safe) |
| Chat broker | **Redis pub/sub relay** — instances publish to a Redis channel; every instance subscribes + re-broadcasts to its local WS subscribers. No new broker process (no RabbitMQ) |
| Admin presence | **Move to Redis** — online-admin SET + per-admin connection counts (replaces per-JVM `ConcurrentHashMap`) |
| Schedulers | **Redis `SET NX EX` leader election** for all four `@Scheduled` methods (also closes the flash-sale TOCTOU). No new dependency |
| Config externalization | Add `FRAME_DB_HOST` / `FRAME_REDIS_HOST` env vars (DB + Redis hosts currently hardcoded `localhost`) |
| Uploads | **Conditional on topology** — single-server-multi-instance → local shared disk (zero deps); multi-host → MinIO self-hosted on the cloud server (free) |
| LB | Nginx `upstream` with N backend targets + WS upgrade headers for `/ws-chat` |
| New cloud deps | None mandatory (Redis already present). MinIO only if multi-host uploads needed |

## 3. Multi-Instance Readiness (current state, verified 2026-08-27)

**Already multi-instance-safe (no change):**
- HTTP JWT auth — `JwtAuthenticationFilter` is stateless crypto, `SessionCreationPolicy.STATELESS` (`SecurityConfig.java:84`).
- Stock gate — shared Redis Lua (`StockGateServiceImpl.java:40-58`).
- One-per-user key, captcha, login-fail, SMS code+cooldown, rate-limit — all shared Redis (`RedisKeys.java:11-22`, `RateLimitFilter.java:49`).
- Product cache — shared Redis read-through (`ProductServiceImpl.java:53-137`).
- Order status/map keys — shared Redis.

**Four blockers (this spec's work):**

| # | Area | File:line | Problem | Fix |
|---|---|---|---|---|
| 1 | Orders queue drain | `StreamConsumerService.java:169-176` | `drain()` does destructive `RPOP` on `orders:pending` List; crash-after-pop loses the order; N instances race-pop. Javadoc (lines 34-78) still *describes* the XREADGROUP design — doc/code drift. | Restore Stream consumer group (§4.1) |
| 2 | Chat broker | `WebSocketConfig.java:22` | `enableSimpleBroker("/topic")` is in-memory; a message published on instance A reaches only A's subscribers. | Redis pub/sub relay (§4.2) |
| 3 | Admin presence | `AdminPresenceRegistry.java:18` | `ConcurrentHashMap<Long,AtomicInteger> presence` is per-JVM; an admin on instance B is invisible to A's `nextOnlineAdmin()` round-robin. | Redis-backed presence (§4.3) |
| 4 | Schedulers | `OrderLifecycleScheduler.java:44,62,80`; `StockGateServiceImpl.java:95`; `StreamConsumerService.java:168` | All four `@Scheduled` fire N times. Three are safe-but-wasteful (DB CAS / idempotency key); `openDueFlashSales` has a TOCTOU on its `hasKey` guard; `drain` is the #1 hard blocker. | Redis `SET NX EX` leader election (§4.4) — `drain`'s guard is moot after §4.1 |

**Minor / config:**
- DB + Redis hosts hardcoded `localhost` in `application-prod.yml:10,24` (and dev `application.yml`) → externalize (§4.5).
- Local filesystem uploads `/var/lib/frame/uploads` → not shared across hosts (§4.6).

## 4. Component & Unit Designs

### 4.1 Orders queue — restore Stream consumer group

**The drift:** `StreamConsumerService` javadoc (lines 34-78) documents an XREADGROUP/XACK-after-commit/XAUTOCLAIM design, but `drain()` (line 169) reverted to plain `RPOP` on the `orders:pending` List. The producer (`CheckoutServiceImpl` Lua gate) must be checked: it should `XADD` to the Stream, not `LPUSH` to the List. (Verify the producer side at implementation; the consumer comment at line 152 notes "cleaned up legacy orders:pending key (was Stream, now List)" — so both sides drifted to List.)

**Restore to the documented design:**
- Producer: `XADD orders:pending * field payload <json>` (not `LPUSH`).
- Consumer init: `XGROUP CREATE orders:pending order-consumers $ MKSTREAM` (idempotent — catch "BUSYGROUP").
- Per-instance consumer name: `consumer:{hostname}:{pid}` (or a random UUID per instance) — so multiple instances each have their own consumer in the group, and Redis tracks each consumer's PEL independently.
- `drain()` (every 100ms): `XREADGROUP GROUP order-consumers {consumerName} COUNT 100 BLOCK 0 STREAMS orders:pending >` — reads only NEW messages for this consumer. On processing: durable insert + DB-backstop stock decrement inside `@Transactional`; `XACK` **only** in a `TransactionSynchronization.afterCommit()` hook (so a crash-before-commit leaves the message in the PEL for redelivery).
- Dead-consumer redelivery: a periodic `XREADGROUP ... 0` (read this consumer's own PEL) + `XAUTOCLAIM` for messages idle > N minutes from *other* consumers (Redis 6.2+). For Redis 5.0.14 (the current verified version, no XAUTOCLAIM): fallback = a leader-elected sweep (§4.4) that `XPENDING` + `XCLAIM` idle messages. Document the version branch.
- Terminal-failure handling: unprocessable messages (parse/NPE) → `XACK` + set order status `FAILED` (existing behavior, preserve).
- Idempotency preserved: the `idempotency_key` UNIQUE constraint remains the sole authority; a redelivered message that already inserted no-ops on the duplicate-key.

**Tests.** Consumer-crash-after-read-before-commit → message redelivered (PEL), order created exactly once (idempotency key). Two consumers (simulate N=2) → both drain, no double-processing.

### 4.2 Chat broker — Redis pub/sub relay

**Replace in-memory broker.** Keep the STOMP layer (SockJS + `/ws-chat` endpoint + `AuthChannelInterceptor` JWT auth) — only swap the broker backing.
- `WebSocketConfig`: remove `enableSimpleBroker("/topic")`. Add a custom relay backed by Redis pub/sub via a `ChannelInterceptor` / `AbstractBrokerMessageHandler` approach:
  - Intercept outbound broker messages (`/topic/**`): instead of the simple broker delivering them in-JVM, publish each to a Redis channel `chat:session:{sessionId}` (extract sessionId from the destination).
  - Every instance subscribes (on startup) to `chat:session:*` (Redis `PSUBSCRIBE`) — on receipt, it calls the local `SimpMessagingTemplate` to deliver to its own local WS subscribers on the matching `/topic/session/{sessionId}` destination.
  - Net: "Redis fan-out, local delivery" — a message published on instance A is delivered to A's + B's + ... local subscribers.
- `ChatStompController.java:33` (`convertAndSend`) unchanged in call — the relay intercepts the publish.
- Ordering/correctness: Redis pub/sub is fire-and-forget; an instance that's subscribed but whose local subscriber disconnected between publish and local delivery drops the message (acceptable — the user fetches history on reconnect; messages are persisted-before-publish already per Phase F).

**Tests.** Two-instance simulation: a message published via instance A's controller is received by a WS client subscribed on instance B. Persistence still happens (persist-before-publish).

### 4.3 Admin presence — Redis-backed

**Replace `AdminPresenceRegistry`'s `ConcurrentHashMap`.**
- Online set: a Redis `SET chat:online-admins` (member = adminId) + a per-admin connection counter `chat:admin-conn:{adminId}` (INCR on connect, DECR on disconnect; `DEL`/`SREM` at 0). Or a `ZSET` with timestamp for staleness.
- `nextOnlineAdmin()`: read `chat:online-admins`, round-robin (cursor in Redis: `INCR chat:admin-rr` mod size).
- Staleness: a periodic sweep (leader-elected §4.4) removes admins whose connection counter is stale / whose heartbeat hasn't ticked.
- `AuthChannelInterceptor` connect/disconnect (`:71-73`, `:118-120`) updated to call the Redis-backed registry instead of the in-JVM one.

**Tests.** Admin connects on instance A → `nextOnlineAdmin()` on instance B sees them.

### 4.4 Scheduler leader election — Redis `SET NX EX`

- A small `DistributedLock` util: `boolean tryLock(String key, Duration ttl)` → `SET key {owner} NX EX {ttlSec}`; `unlock` → Lua compare-and-delete (only delete if value==owner).
- Wrap each `@Scheduled` method's body: acquire `shedlock:{taskName}` (ttl slightly > the cadence interval); only the winner runs; others skip.
- Applies to:
  - `expirePendingOrders` (60s) — already DB-CAS-safe; lock avoids wasted N-fold queries.
  - `autoCompleteOrders` (daily cron) — idempotent via CAS; lock avoids N-fold.
  - `sweepExpiredCoins` (daily cron) — idempotent via `idem_key`; lock avoids N-fold.
  - `openDueFlashSales` (10s) — **also closes the TOCTOU**: the lock serializes the `hasKey`→`loadStock` window so two instances can't both see `hasKey==false` and re-load stock mid-flight.
- `drain()` (100ms): the Stream consumer group (§4.1) makes this multi-consumer-safe by design; **no leader lock needed** (each instance drains its own PEL). Do not add a lock to `drain`.

**Tests.** Two-instance simulation: only one acquires the lock per tick; the other no-ops.

### 4.5 Config externalization

- `application-prod.yml`: `spring.datasource.url` host → `${FRAME_DB_HOST:localhost}`; `spring.data.redis.host` → `${FRAME_REDIS_HOST:localhost}`. Keep the rest of the URL (port, db name, params) parameterized too (`FRAME_DB_PORT`, `FRAME_DB_NAME`) for flexibility.
- `deploy/frame-backend.service`: add `Environment=FRAME_DB_HOST=...` + `FRAME_REDIS_HOST=...` lines.
- Dev `application.yml`: leave `localhost` (or also externalize, harmless).
- JWT secret, SMS, CORS already env-var (no change).

### 4.6 Shared uploads (conditional on topology — §6)

- **If single-server-multi-instance** (both app instances on `172.29.231.145`): no change — both instances share the server's filesystem; uploads to `/var/lib/frame/uploads` are instantly visible to both. Zero new deps.
- **If multi-host** (instance on the cloud server + instance on the VirtualBox VM, or two cloud servers): self-host **MinIO** on `172.29.231.145` (systemd service, data dir on the server's disk — free, no bill). Add `io.minio:minio` to the pom. `ProductServiceImpl.uploadImage` + `ReviewImageServiceImpl` + avatar upload → `minioClient.putObject(bucket, key, stream)` → store the returned URL. All instances fetch via the MinIO HTTP endpoint. New env vars: `FRAME_MINIO_ENDPOINT`, `FRAME_MINIO_ACCESS_KEY`, `FRAME_MINIO_SECRET_KEY`, `FRAME_MINIO_BUCKET`.
- MinIO endpoint only needs to be reachable from the other host(s); for single-server it's not needed at all.

## 5. Deployment Topology (decided by recon — §6)

Two candidate topologies, picked by what the recon shows is free on `172.29.231.145`:

**Topology T1 — single-server, 2 app instances (likely).** All on `172.29.231.145`: Nginx (LB) + 2× backend jar (ports 8081, 8082) + MariaDB + Redis. If the existing VirtualBox VM is kept as the 2nd host for a "true two-host" demo, point its jar at the cloud server's MariaDB/Redis (externalized §4.5) + cloud MinIO. Otherwise run both instances on the cloud server. Uploads: §4.6 single-server branch (local disk). This exercises all four §4 fixes.

**Topology T2 — two hosts.** App on cloud server + app on VirtualBox VM (or a 2nd cloud box), shared MariaDB+Redis+MinIO on the cloud server. True distributed variant; uploads: §4.6 multi-host branch (MinIO).

**Port discipline (hard constraint):** recon lists in-use ports on `172.29.231.145`; the deployment picks 8081/8082/nginx-port that don't conflict. If 8081 is taken, use 8091/8092 etc. Document the chosen ports in the deploy guide.

## 6. Recon (gated, read-only — resolves topology + uploads)

The recon command is read-only (OS, CPU/RAM/disk, listening ports, running services). It's gated behind the SSH auto-mode guardrail — the **user runs it via the `!` prefix**:

```
! ssh -o StrictHostKeyChecking=no root@172.29.231.145 'echo "=== OS ==="; cat /etc/os-release 2>/dev/null | head -2; echo "=== CPU/MEM ==="; nproc; free -h | head -2; echo "=== DISK ==="; df -h / 2>/dev/null; echo "=== LISTENING PORTS ==="; ss -tlnp 2>/dev/null | head -40 || netstat -tlnp 2>/dev/null | head -40; echo "=== RUNNING SVCS ==="; systemctl list-units --type=service --state=running --no-pager 2>/dev/null | head -30'
```

**What the recon decides:**
- **Topology T1 vs T2:** if the cloud server has comfortable CPU/RAM (≥2 cores, ≥4GB free) → T1 (single-server, 2 instances) is simplest and avoids touching the VirtualBox VM. If resources are tight, T2 (offload an instance to the VirtualBox VM).
- **Port choices:** from the listening-ports list, pick non-conflicting 8081/8082/nginx.
- **Uploads branch:** T1 → local disk; T2 → MinIO.
- **Existing services to avoid:** the running-services list tells us what NOT to disturb (e.g., if MariaDB/Redis are already running there with different data, we either reuse carefully or run our own on different ports — never overwrite existing data).

## 7. Out of Scope

- The feature round (separate spec, sequenced before this one).
- 99.95% availability / long-run monitoring / failover (operational SLA — beyond this milestone; single cloud server insufficient for true HA).
- OAuth2.0 (explicitly deferred project-wide; JWT retained).
- LLM customer-service bot (dropped).

## 8. Risks

- **Redis 5.0.14 vs 6.2 (XAUTOCLAIM):** the current verified Redis has no XAUTOCLAIM. §4.1's dead-consumer redelivery must branch on Redis version (XPENDING + XCLAIM fallback). Confirm the cloud server's Redis version during recon; if newer, prefer XAUTOCLAIM.
- **Existing services on the cloud server:** the hard constraint is to not disturb them. Recon MUST run before any write. If MariaDB/Redis are already in use there with different data, do not reuse that DB/instance — run our own on different ports, or use the VirtualBox VM's DB/Redis as the shared store instead. Decide at plan time.
- **Producer-side drift:** §4.1 assumes the Lua-gate producer XADDs to the Stream. Verify the producer actually does (the consumer comment suggests both sides drifted to List). If the producer LPUSHes, fix the producer too.
- **Chat pub/sub message loss on subscribe-race:** acceptable (history-fetch on reconnect), but document it so it's a known limitation, not a surprise.
