# Design — Frame Shop Phase F (Live Chat, UC011 + NFRs + finalization)

> Date: 2026-08-21. Branch `feature/phase-f-chat` off `main` (HEAD `fb3bf9a`, Phase E merged). Detailed spec under the umbrella `2026-08-19-frame-shop-uc-fulfillment-design.md` §8; this document is the detailed Phase F spec. Covers **UC011** (联系客服 — real-time user↔admin chat via WebSocket) + the NFR **性能** artifacts (10k QPS load proof, image lazy-load) + the deferred-items cleanup + the Aliyun SMS activation + VM redeploy runbook. This is the final phase of the UC000-UC018 fulfillment.
>
> UC011 (联系客服), per `use case/use cases.txt:135-143`: a registered, logged-in user clicks 联系客服 → the system creates a chat session with customer service → user and 客服 (admin) exchange messages with **real-time transmission** (实时消息传输) → messages are **stored** (聊天信息已存储).
>
> **Status going in:** Phases A–E merged to `main`. Backend 154/154, frontend 48/48, e2e 35 green / 13 failing (the SMS-cascade). The VirtualBox VM runs stale Phase-B/C code (deploy deferred since Phase D). The local dev backend on `:8081` runs the rebuilt Phase E code. `LoggingVerificationProvider` (dev stub) is the active SMS provider; `AliyunVerificationProvider` is a verified skeleton for the 短信认证服务 variant.

## 1. Scope (locked)

### In scope — live chat (UC011)
- **Real-time user↔admin messaging** over STOMP+SockJS. A user opens a chat session; messages push to the other party in real time; every message is persisted.
- **Per-user sessions + admin inbox**: one open `chat_session` per user; an admin inbox shows open sessions; the first user message **auto-assigns** to an online admin (round-robin over admins with active STOMP connections); if no admin is online, the message lands in the inbox for the first admin to pick up on connect.
- **Message history**: a REST endpoint rehydrates a conversation on reconnect/refresh.
- **Frontend chat UI**: a user chat page + an admin inbox view.

### In scope — NFR artifacts
- **10k QPS load proof** (性能: 10000 QPS, 响应 < 2s): add a Redis read-through cache to the uncached `GET /products` list path (the hottest read path — today every list request hits MariaDB); author a load-test script; document the result (pass or documented bottleneck + two-host scaling path).
- **Image lazy-load** (性能: 图片懒加载, 首屏 ≤ 1.5s): audit all `<img>`; add `loading="lazy"` to the one missing instance (review thumbnails). First-screen check documented in the final regression.

### In scope — functional cleanup (per "functional + e2e cascade only" bar)
- **e2e SMS-cascade fix** (13 failures): the 3 stale 2-arg `POST /auth/register` calls → 5-arg contract with a Redis pre-seed of the verification code (the pattern already proven in `CheckoutFlowIntegrationTest`).
- **`@Scheduled` timezone**: the coin-expiry sweep uses the JVM default tz; fix to explicit `Asia/Shanghai` so a UTC VM still sweeps at 4:01am CST.
- **`CartContext.loadInitial` isFlashSale default**: stale localStorage carts lack `isFlashSale` → backend flash-gate 400 catches it but with the wrong UX; default to `false`.
- **`ORDER_SEQ` collision-hardening**: wall-clock-millis seeding is collision-prone across JVM restarts (the e2e flakiness root cause); minimal in-process atomic-seed replacement.

### In scope — deploy wiring
- **Aliyun SMS SDK wiring**: replace the `AliyunVerificationProvider` skeleton's TODO calls with the confirmed 短信认证服务 SDK calls (`SendVerificationCode` / `VerifyVerificationCode`). The 短信认证服务 variant means **no 短信签名/模板 approval needed** (Aliyun generates + verifies the code server-side) — a real simplification over the original dysmsapi assumption.
- **VM redeploy runbook + final regression**: delivered as a runbook for the user (rebuild jar → scp via port 2222 → systemd restart → surface UC010+UC011+UC012); the final full regression re-run.

### Out of scope (deferred / not in this version)
- **The cosmetic deferred-minors backlog** (dead imports, missing `CoinMapper.xml` resultMap, `CoinDiscountPreview` VO suffix, dead test autowires, coins-page first-50 pagination, the Phase A/B/C/D minor review findings) — explicitly left per the "functional + e2e cascade only" cleanup bar. Folded in only if a functional fix touches that file anyway.
- **Aliyun activation itself** (creating the instance + the real access key + the manual SMS verify) — needs the user's Aliyun credentials; delivered as a runbook.
- **The VM redeploy execution itself** — needs the user's VM; delivered as a runbook.
- **OAuth2.0** — still deferred from the umbrella scope (standing decision).
- **Two-host distributed deployment** — still the next milestone; Phase F's 10k QPS doc points at it as the scaling path if the single host falls short.

## 2. Decisions (locked)

| Decision | Choice | Why |
|---|---|---|
| Chat transport | **STOMP over SockJS** via `spring-boot-starter-websocket`; `WebSocketConfig implements WebSocketMessageBrokerConfigurer`; endpoint `/ws-chat` with SockJS fallback; `enableSimpleBroker("/topic")` + `setApplicationDestinationPrefixes("/app")`. | The umbrella §8 left "STOMP (or raw WebSocket handler)" open. STOMP gives `@MessageMapping` routing, topic subscriptions, and ack semantics for free; SockJS gives transport fallback for browsers/proxies that don't do raw WS. Most defensible for the mentor; cleanest fit for the existing Spring Boot servlet stack. |
| WS auth | **JWT in the STOMP CONNECT frame header**, read by an `AuthChannelInterceptor` (implements `ChannelInterceptor`, `preSend` on CONNECT) → validates via existing `JwtUtil` → stamps `SecurityUser` into `accessor.user`. | The browser WS API can't set the `Authorization` header; `JwtAuthenticationFilter` only reads the header (no cookie flow exists). Passing the JWT in the CONNECT header reuses the existing header-only JWT model with **zero** changes to `JwtAuthenticationFilter` or any cookie. No token in URLs (avoids the log/referrer leak smell). |
| Admin authz on chat | Role check in the `@MessageMapping` handler — the handler branches on `principal.role == ADMIN` (admin reply) vs `USER` (user message) and `ChatService` re-checks the role/ownership on each send. REST admin endpoints under `/admin/chat/**` → `hasRole("ADMIN")` (existing URL-matcher pattern). | The `Role{USER,ADMIN}` enum + `ROLE_ADMIN` authority is already wired into `SecurityConfig` for `/admin/**`; chat admin routing reuses it directly. One enforcement mechanism (in-handler + service re-check), not two. |
| Session model | **One open `chat_session` per user.** `POST /chat/sessions` finds an existing OPEN session for the user or creates one; if the user has a CLOSED session, create a new OPEN one. | UC011: "系统创建与客服的聊天界面" — a user has one ongoing conversation with customer service. One-open-session-per-user is the simplest model that fits; reopening vs. new-on-close keeps history queryable per session. |
| Admin assignment | **Auto-assign on first user message** to an online admin, round-robin over admins with an active STOMP connection (tracked in a `ConcurrentHashMap<Long adminId, presence>` maintained by connect/disconnect events in the channel interceptor). If no admin online → session stays `admin_id IS NULL`, message lands in the inbox for the first admin to pick up on connect (fail-safe, no message lost). | User decision: "the first user message auto-assign to whichever admin is online." Round-robin balances load; the no-admin-online fallback means a message is never dropped — it persists and surfaces when an admin connects. |
| REST vs WS split | **Session lifecycle + history = REST** (`POST /chat/sessions`, `GET /chat/sessions/{id}/messages`); **real-time push = WS only** (`@MessageMapping` → `convertAndSend` to `/topic/session/{id}`). | Mutations/reads are simple + cacheable + testable as REST; the broker is purely for push. Mirrors how the existing app keeps mutations in controllers. The real-time guarantee is proven by the STOMP integration test, not the fetch-based e2e. |
| Message persistence | Every inbound message is persisted to `chat_message` **before** being sent to the broker. | UC011 postcondition: "聊天信息已存储". Persist-then-publish guarantees no message is lost even if the broker publish fails; the history endpoint rehydrates from the DB. |
| Ownership/role defense | Every `@MessageMapping` handler re-checks: user messages require `principal.userId == session.user_id`; admin replies require `ROLE_ADMIN`. | Defense-in-depth (the channel interceptor already authed the connection, but a user could `SEND` to another user's session path). Same ownership-before-action pattern as the order endpoints. |
| `GET /products` cache | **Read-through Redis cache** keyed by a deterministic hash of the **normalized** `PageQuery` (page, size, sortBy, sortOrder, category, keyword after normalization); TTL 30s (matches `getById`'s `CACHE_TTL_SECONDS`); invalidation on product create/update/delete via a Redis `SET products:page:keys` of live list keys (DEL members + the set on mutation — no `KEYS`/`SCAN`); graceful degradation catching `RedisConnectionFailureException`. | The list path is the hottest read and the 10k QPS target; a 30s-stale browse cache is fine and bounds cache size. Normalized-key hashing makes `?sort=price&order=asc` and `?order=asc&sort=price` hit the same key. The SET-based invalidation avoids `KEYS`/`SCAN` (which block Redis — critical for the very perf being proven). Mirrors the existing manual-`StringRedisTemplate` + degradation pattern in `getById`. |
| Load-test tool | **Self-contained Node script** under `e2e/load/products-load.mjs` reusing `e2e/lib/http.mjs` patterns (no k6/autocannon — reuses the toolchain already present, no new runtime to install). Hits `GET /products` at increasing RPS, measures p95, writes a result report to `e2e/out/`. | The repo has no load-test script today; Node reuses the existing e2e toolchain. k6 would need a separate install + runtime. |
| 10k QPS acceptance | Cache + proof + **document the honest result**. If the single dev/VM host hits 10k req/s p95 < 2s → "PASS". If it falls short → document the bottleneck (likely HikariCP ceiling, Nginx rate-limit zone, or single Redis connection) + the two-host distributed scaling path (stateless JWT + shared Redis/MariaDB). | Umbrella §8: "If the single-VM can't hit 10k QPS, document the bottleneck and the two-host distributed variant." Either outcome is a valid deliverable. No aggressive tuning rabbit hole unless the result is *close* and one obvious tweak crosses the line. |
| e2e SMS-cascade fix | A new `e2e/lib/sms.mjs` helper that **pre-seeds** `sms:code:REGISTER:{phone}` in Redis (known code `123456`, 5-min TTL) before the register POST, then sends the 5-arg `{username, password, phone, code, agreed}` payload. Applied to the 3 stale register calls (`auth.test.mjs:9-20`, `concurrency.test.mjs:54`, `checkout.test.mjs:65`). e2e runner gets Redis access at `localhost:6379` (no password). | The exact pattern already proven in `CheckoutFlowIntegrationTest.java:68-74`. No env-var bypass of SMS verification is added — the code is genuinely verified, just pre-seeded so the harness can supply it. The prod path is untouched. Unblocks the cascade: register passes → downstream suites get a real user token. |
| `@Scheduled` tz | `@EnableScheduling(timeZone = "Asia/Shanghai")` on the scheduler config (or the `zone` attribute on the cron). | The coin-expiry sweep (`"1 1 4 * * *"`) uses the JVM default tz; a UTC VM would sweep at 4:01am UTC not CST. Consistent with the app's `Asia/Shanghai` setting. |
| `ORDER_SEQ` hardening | Minimal in-process collision-safe generator: an `AtomicLong` seeded once at startup (process-unique), not a full distributed-Snowflake redesign. | One JVM, so an in-process atomic seed is enough to remove the cross-restart collision that caused e2e flakiness. A distributed-ID redesign is YAGNI for a single-host deployment. |
| e2e-vs-integration split for chat | The fetch-based e2e covers the **REST** chat surface (open session, fetch history, ownership 403); the real-time STOMP push is proven by a backend **`WebSocketStompClient` integration test**. | The e2e runner speaks `fetch`, not STOMP. Honest split: REST contract in e2e, real-time push in integration. |
| SMS tests | Still none (standing scope decision). Aliyun verified manually by the user. | Consistent with phases A–E. |

## 3. Data model

### 3a. New `chat_session` + `chat_message` tables — added to `schema.sql` (drop-if-exists first) and `deploy/schema-prod.sql`

```sql
CREATE TABLE `chat_session` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT       NOT NULL,
    `admin_id`    BIGINT,                       -- NULL until an admin is assigned
    `status`      VARCHAR(16)  NOT NULL DEFAULT 'OPEN',  -- OPEN / CLOSED
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_session_user` (`user_id`),
    INDEX `idx_session_admin` (`admin_id`),
    INDEX `idx_session_status` (`status`)
);

CREATE TABLE `chat_message` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `session_id`  BIGINT       NOT NULL,
    `sender_type` VARCHAR(16)  NOT NULL,        -- USER / ADMIN
    `sender_id`   BIGINT       NOT NULL,
    `content`     TEXT         NOT NULL,
    `image_url`   VARCHAR(512),
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_msg_session` (`session_id`)
);
```

### 3b. Entities + mappers
- `ChatSession` entity (extends `BaseEntity`), `ChatSessionMapper` (MyBatis-Plus).
- `ChatMessage` entity, `ChatMessageMapper`.
- Consistent with the existing 7-entity / 7-mapper layout.

### 3c. No schema change for the `GET /products` cache or the cleanup items
- The list cache is Redis-only (no new column/table).
- `@Scheduled` tz, `CartContext` isFlashSale default, `ORDER_SEQ` hardening are code-only.

## 4. Live chat (UC011) — detailed design

### 4a. Backend: WebSocketConfig + AuthChannelInterceptor

`WebSocketConfig` (in `.../frame/config/`):
```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-chat")
                .setAllowedOriginPatterns(/* from frame.cors.allowed-origins */)
                .withSockJS();
    }
}
```

`AuthChannelInterceptor`:
```java
@Component
@RequiredArgsConstructor
public class AuthChannelInterceptor implements ChannelInterceptor {
    private final JwtUtil jwtUtil;
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor
                .getAccessor(message, StompHeaderAccessor.class);
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String token = accessor.getFirstNativeHeader("Authorization"); // "Bearer ..."
            // strip prefix, jwtUtil.parse, build SecurityUser, accessor.setUser(securityUser)
            // invalid/expired → throw MessagingException (reject the CONNECT)
        }
        return message;
    }
}
```
Registered via `WebSocketConfig.configureClientInboundChannel` (add the interceptor to the registry's interceptors).

**Admin presence tracker**: the `AuthChannelInterceptor` (or a `SessionConnectEvent` / `SessionDisconnectEvent` listener) maintains a `ConcurrentHashMap<Long adminId, Integer presenceCount>` for connected admins. On CONNECT of a `ROLE_ADMIN` principal → increment; on DISCONNECT → decrement; when count hits 0 → remove. Round-robin assignment reads the keyset.

### 4b. Backend: ChatService + controllers

`ChatService` (interface) + `ChatServiceImpl`:
- `ChatSession findOrCreateOpenSession(Long userId)` — `SELECT ... WHERE user_id=? AND status='OPEN'`; if none, INSERT. Returns the session.
- `void assignAdminIfNeeded(ChatSession session)` — if `admin_id IS NULL`, pick the next online admin (round-robin over the presence tracker keyset); UPDATE `admin_id`. If none online, no-op (stays in inbox).
- `ChatMessage sendUserMessage(Long sessionId, Long userId, String content, String imageUrl)` — ownership check; INSERT `USER` message; `assignAdminIfNeeded`; return the message (the controller publishes it to the broker).
- `ChatMessage sendAdminMessage(Long sessionId, Long adminId, String content, String imageUrl)` — role check; INSERT `ADMIN` message; return.
- `PageResult<ChatMessage> history(Long sessionId, int page, int size)` — `WHERE session_id=? ORDER BY create_time`.

`@MessageMapping` handlers (a `ChatStompController`):
```java
@MessageMapping("/chat/{sessionId}")
public void handleChatMessage(@DestinationVariable Long sessionId, ChatMessageRequest req, Principal principal) {
    SecurityUser su = (SecurityUser) principal;
    ChatMessage msg = su.role() == Role.ADMIN
        ? chatService.sendAdminMessage(sessionId, su.userId(), req.content(), req.imageUrl())
        : chatService.sendUserMessage(sessionId, su.userId(), req.content(), req.imageUrl());
    messagingTemplate.convertAndSend("/topic/session/" + sessionId, toVO(msg));
}
```
The `@MessageMapping` handler **persists** (via `ChatService`) then **publishes** — persist-before-publish.

REST controllers:
- `ChatController` (USER): `POST /chat/sessions` (find-or-create) → `{sessionId}`; `GET /chat/sessions/{id}/messages?page=&size=` (owner only).
- `AdminChatController` (ADMIN): `GET /admin/chat/sessions?status=OPEN` (inbox list); `GET /admin/chat/sessions/{id}/messages` (history).

`SecurityConfig` additions: `/ws-chat/**` permitAll (handshake through; auth in the channel interceptor); `POST /chat/sessions`, `GET /chat/sessions/**` → `hasRole("USER")`; `/admin/chat/**` → `hasRole("ADMIN")`.

### 4c. Frontend: useChat hook + Chat.tsx + admin inbox

- `src/api/chat.ts` — REST: `openSession()`, `fetchHistory(sessionId, page)`.
- `src/hooks/useChat.ts` — manages the `@stomp/stompjs` + `sockjs-client` client: connect to `/ws-chat` with the `Authorization` header (token from `useAuthStore`), subscribe to `/topic/session/{sessionId}`, send to `/app/chat/{sessionId}`, auto-reconnect on disconnect, expose `{messages, send, connected}`.
- `src/pages/Chat.tsx` — on mount: `openSession()` → connect STOMP → render message list + input + image-attach (reuse the review-image uploader pattern). Route `/chat` (USER, behind `RequireAuth`).
- Admin inbox in `Admin.tsx`: list open sessions, click one → subscribe to `/topic/session/{id}` + load history → reply.

## 5. NFR: 10k QPS proof — detailed design

### 5a. `GET /products` list cache (the code change)

In `ProductServiceImpl.page(query)` (currently straight to `mapper.pageProducts`):
1. **Normalize** the query (already done today: sortField whitelist, sortOrderSql derivation, keyword split). Build the cache key from the **post-normalization** values: `products:page:` + SHA-256 of the normalized `(page, size, sortBy, sortOrder, category, normalizedKeyword)`.
2. **Read-through**: `redis.opsForValue().get(key)` → deserialize `PageResult<ProductListItem>` via Jackson. On hit → return. On miss → `mapper.pageProducts` → `redis.opsForValue().set(key, json, 30, SECONDS)` → return. Catch `RedisConnectionFailureException` on both → fall through to DB (degradation, like `getById`).
3. **Track the key**: on cache write, `redis.opsForSet().add("products:page:keys", key)`.
4. **Invalidation** on `create()` / `update()` / `delete()` (the same methods that already `redis.delete("product:"+id)`): read the `products:page:keys` set, `DEL` each member + `DEL products:page:keys`. (A single mutation invalidates the whole list cache — simplest correct; list views are browse-y, 30s staleness is acceptable, and this avoids per-key staleness bugs.)

### 5b. Load-test script

`e2e/load/products-load.mjs`:
- Reuses `e2e/lib/env.mjs` (BASE_URL) + the `fetch` shape from `e2e/lib/http.mjs`.
- Runs a fixed-duration test (e.g. 30s) at increasing RPS targets (1k → 5k → 10k), each stage measuring throughput + p95 latency.
- Writes a markdown report to `e2e/out/load-<timestamp>.md` (target RPS, achieved RPS, p95, p99, error rate, verdict).
- Run against the local dev backend on `:8081` (`E2E_BASE_URL=http://localhost:8081`).

### 5c. Acceptance + documentation

- Run the script after the cache lands. Capture the result.
- If PASS (10k req/s, p95 < 2s): record "PASS" + the numbers in the status report.
- If falls short: record the achieved number + the identified bottleneck + the two-host scaling path (stateless JWT + shared Redis/MariaDB → load-balancer in front). Either is a valid deliverable per umbrella §8.

## 6. Cleanup — detailed design

### 6a. e2e SMS-cascade fix
- New `e2e/lib/sms.mjs`. The e2e runner is pure Node built-ins (no `package.json`, no `node_modules`, no Redis client lib), but `redis-cli` is on PATH (`/c/Program Files/Redis/redis-cli`, passwordless `localhost:6379` — the same Redis the dev backend reads `sms:code:` from). So the helper shells out to `redis-cli` to pre-seed the code — no new dependency:
  ```js
  import { execSync } from 'node:child_process';
  export async function registerWithCode(http, ctx, { username, password }) {
      const phone = '139' + String(Date.now()).slice(-8);
      const code = '123456';
      // pre-seed sms:code:REGISTER:{phone} in Redis (5-min TTL), same key the backend reads
      execSync(`redis-cli SET "sms:code:REGISTER:${phone}" "${code}" EX 300`);
      const res = await http.post('/auth/register', { username, password, phone, code, agreed: true });
      return { phone, ...res };
  }
  ```
  (If `redis-cli` is ever absent, fall back to a minimal raw-RESP helper over `node:net` — but `redis-cli` is confirmed on PATH today, so it's the path of least resistance.)
- Applied to `auth.test.mjs:9-20`, `concurrency.test.mjs:54` (50 buyers → register each with a unique phone + pre-seeded code), `checkout.test.mjs:65`.

### 6b. `@Scheduled` timezone
- Add `@EnableScheduling(timeZone = "Asia/Shanghai")` (or the `zone` attribute on the coin-expiry `@Scheduled`). Verify the existing schedulers still fire correctly.

### 6c. `CartContext.loadInitial` isFlashSale default
- In the localStorage-cart item mapping, default `isFlashSale: false` when absent.

### 6d. `ORDER_SEQ` hardening
- Replace the wall-clock-millis seed with an `AtomicLong` seeded once at startup (e.g. `System.currentTimeMillis() << 20 | counter`) — process-unique, no cross-restart collision. Minimal change; not a distributed-ID redesign.

### 6e. Image lazy-load
- Add `loading="lazy"` to `ReviewModal.tsx:86` thumbnail `<img>`. Audit confirms the two product images already have it. First-screen ≤1.5s check = a documented manual confirmation against the dev frontend in the final regression.

## 7. Deploy wiring — detailed design

### 7a. Aliyun SMS SDK wiring
- In `AliyunVerificationProvider.send(phone, purpose)`: build the Aliyun client from `props.getAliyun()` (access-key-id/secret/region/serviceCode), call `SendVerificationCode(phoneNumber=phone)`. On `ClientException`, log and degrade (do NOT throw — the send-code endpoint degrades gracefully; the dev stub remains the working default).
- In `verify(phone, code, purpose)`: call `VerifyVerificationCode(phoneNumber=phone, code=code)`, return its pass/fail. Aliyun consumes the code on its side (one-shot).
- **Runbook for the user** (delivered in the status doc): create the Aliyun 短信认证服务 instance → drop the access-key-id/secret/region/serviceCode into the prod config → flip `frame.sms.provider=aliyun` → verify one real SMS send.

### 7b. VM redeploy runbook (for the user)
- Rebuild the jar (`mvn -pl frame-backend clean package -DskipTests`), scp to the VM (`scp -P 2222 frame-backend/target/*.jar tcmao@localhost:~/`), `sudo systemctl restart frame-backend`, smoke-test `http://localhost:8888`. Surfaces UC010 + UC011 + UC012 on the live VM.
- Final regression re-run on the VM.

## 8. Testing strategy

- **Backend unit**: `ChatService` (session find-or-create, ownership, admin auto-assign round-robin + no-admin-online fallback), `AuthChannelInterceptor` (valid→principal, invalid→reject), `ProductServiceImpl` list-cache key normalization.
- **Backend integration**: `ChatIntegrationTest` (`@SpringBootTest` + real Redis/MariaDB) using Spring's `WebSocketStompClient` — open session, send user message, assert it reaches `/topic/session/{id}` + is persisted; admin reply reaches the user. A list-cache integration test (hit/miss/invalidation/Redis-down degradation). The `AuthChannelInterceptor` round-trips through here too.
- **Frontend (Vitest)**: `useChat` hook (connect/subscribe/send/reconnect) + `Chat.tsx` rendering. Mock the STOMP client at the `stompjs` boundary (not axios — matches the Phase B final-review smell about mocking at the right boundary).
- **E2E**: `chat.test.mjs` covering the REST surface (open session, fetch history, ownership 403 when user A reads user B's session). The real-time STOMP path is the integration test's job.
- **No SMS tests** (standing scope). Aliyun verified manually by the user.
- **Final regression**: full re-run — backend (154 → +chat/cache/cleanup), frontend (48 → +chat), e2e (35 green / 13 failing → the 13 should resolve with the cascade fix; catalog 7 + orders 11 + reviews 6 + coins 11 stay green, +chat REST cases).

## 9. Build order (single branch, foundation-first)

1. **Branch + baseline**: `feature/phase-f-chat` off `main` (`fb3bf9a`). Record starting test counts (backend 154, frontend 48, e2e 35g/13f).
2. **Foundation (early — chat e2e + load proof build on these)**: e2e SMS-cascade fix (§6a), `@Scheduled` tz (§6b), `CartContext` isFlashSale (§6c), `ORDER_SEQ` hardening (§6d), lazy-load (§6e). Run the now-green e2e.
3. **`GET /products` list cache** (§5a) + integration test.
4. **Live chat backend**: `WebSocketConfig` + `AuthChannelInterceptor` + entities/mappers + `ChatService` + `@MessageMapping` handlers + REST endpoints. Backend tests.
5. **Live chat frontend**: `api/chat.ts` + `useChat` + `Chat.tsx` + admin inbox. Frontend tests.
6. **Chat e2e** (REST surface) + final review + merge.
7. **Aliyun SDK wiring** (code-only; runbook waits for the user's key) — can land before merge or as the first post-merge commit.
8. **VM redeploy runbook + final regression re-run** — delivered to the user.

Same subagent-driven build as phases A–E: per-task implementer + per-task review + final whole-branch opus review, under auto mode. Per-task reviews carry the weight (the branch is large); the final review is the gate.

## 10. Traceability (Phase F scope)

| UC / NFR | Feature | Phase F status |
|---|---|---|
| UC011 | live chat | built |
| NFR perf (10000 QPS, <2s) | list cache + load proof | proof artifact (pass or documented bottleneck) |
| NFR perf (图片懒加载, ≤1.5s) | lazy-load audit + fix | built + documented check |
| e2e SMS-cascade | 13 failures → green | fixed |
| (deferred) Aliyun SMS | SDK wired | code done; activation = user runbook |
| (deferred) VM redeploy | surface D+E+F on VM | runbook for user |
| OAuth2.0 | (still deferred) | noted as deferred |
