# Design — Frame Shop UC Fulfillment (UC000-UC018 + NFRs)

> Date: 2026-08-19. This phase turns the Frame Shop flash-sale MVP into a full e-commerce platform satisfying every use case in `use case/use cases.txt` (UC000-UC018) plus the non-functional requirements, minus OAuth2.0 (explicitly deferred to save time).
> Built on top of the existing `frame-backend` + `frame-frontend` after merging `e2e-test-suite`→`main`.
> Verification channel is **SMS to phone (手机号)** everywhere — NOT email. SMS provider is **Aliyun** (Chuanglan doesn't serve individuals), built behind a `SmsSender` interface.

## 1. What we're building

A full e-commerce platform: browse/search/sort, SMS-gated registration, cart, the full order lifecycle (unpaid→paid→shipped→delivered→reviewed/completed, with cancel+refund including shipped→admin-approval), 3-dimensional star reviews with images, a coins economy (daily-login + review reward + FIFO expiry + checkout discount), real-time customer-service chat, admin stock/shipping/refund management, profile editing (nickname/avatar/signature), password reset, phone rebind, account lockout after 10 wrong passwords, plus the NFRs: HTTPS全站加密, operation logging, 10k QPS, image lazy-load. OAuth2.0 is **deferred** — the existing JWT auth is kept.

This is large, so it is decomposed into **6 phases, layered bottom-up (A→F)**, each with its own spec→plan→build→test cycle. This document is the umbrella design covering all phases; each phase's implementation plan will be written separately.

### Scope decisions (locked)

- **OAuth2.0 dropped** from this version. The use cases' "安全性: 采用OAuth2.0认证机制" NFR is deferred — existing JWT auth retained. No `spring-boot-starter-oauth2-*` dependency.
- **No SMS test scripts.** The `SmsSender` interface + provider impl are built and wired into all verification flows, but no automated tests assert SMS sending. The user verifies SMS manually at the end.
- **Skip-and-return policy.** If a test fails and the bug is hard to fix, skip it — log it in `docs/superpowers/skipped-issues.md`, keep moving to the next work, and fix the skipped bugs at the final stage. Do not block a phase on a single hard bug.
- **UC005/UC006 merged** into one checkout flow with a conditional coins branch (coins branch activates only after Phase E lands; until then checkout behaves as UC006).

## 2. Phase plan (layered bottom-up)

| Phase | Theme | UCs | New infra | Est. days |
|---|---|---|---|---|
| 0 | Merge `e2e-test-suite`→`main`, re-run baseline | — | — | 0.5 |
| **A** | Foundation & security: User phone/nickname/avatar/signature, SMS sender, verification-code service, account lockout, password reset, phone rebind, operation logging, HTTPS | UC001, UC002(phone login), UC015, UC016, UC017, UC018 + NFR HTTPS/op-log | `SmsSender` interface (Aliyun impl + `LoggingSmsSender` dev stub), verification-code Redis store, `login_attempt` lockout, `operation_log` AOP, TLS config | 4–5 |
| **B** | Catalog: keyword search, multi-criteria sort (price/sales/rating asc+desc), product detail page | UC000 (+partial UC013) | keyword LIKE search, sales/rating aggregations | 1.5 |
| **C** | Order lifecycle & refunds: SHIPPED/DELIVERED/REFUNDED states, 30-min unpaid expiry, cancel+refund (incl. shipped→admin approval), admin shipping & refund management | UC004, UC005/006(merged), UC007, UC008, UC009, UC014 | order state machine, expiry scheduler, refund flow, admin order endpoints | 3 |
| **D** | Reviews: 3-D star ratings (quality/logistics/service), image upload (jpg/png ≤20MB), edit-after-submit; rating feeds back to B's rating-sort | UC010 | `review` + `review_image`, avg-rating aggregation | 2 |
| **E** | Coins: daily-login reward (100/day, 4am CST refresh), review reward (= paid amount), 30-day per-credit FIFO expiry, checkout discount rules (100coins=1元, ≤50% of undiscounted, no coins on discount items) | UC012 + completes UC005 coin branch | `coin_ledger` (FIFO), coins-at-checkout | 2.5 |
| **F** | Live chat (WebSocket) + image lazy-load + 10k QPS load proof + skipped-bug cleanup + final regression | UC011 + NFR perf/lazy-load | `spring-boot-starter-websocket`, chat entities, load test | 2.5 |
| | | | **Total** | **~19–22** |

### Branch strategy

- Phase 0: merge `e2e-test-suite`→`main` (fast-forward or PR-merge; keeps the ObjectMapper cache fix + 26 e2e tests + 29 backend tests as a regression gate).
- One feature branch per phase off `main`: `feature/phase-a-foundation`, etc. Merge each into `main` before the next phase branches, so the e2e suite re-runs after every phase as a regression gate. **DECIDED: one branch per phase, merged in sequence.**

### Dependency notes (why this order)

- Phase A is the root: phone rebind, lockout, and password reset all need the SMS service introduced in A; OAuth2/HTTPS/op-log touch every endpoint so cheapest to lay down early (OAuth2 now removed, but HTTPS/op-log remain).
- Phase B builds the sort infrastructure with a placeholder rating (0 stars) until reviews exist; Phase D plugs real ratings into the same sort path. No rework — the sort field is computed from a `review` aggregation that returns 0 when no reviews.
- Phase C must precede D (you can only review *signed/delivered* orders) and E (review reward needs a review to exist).
- Phase E's coins-at-checkout lights up UC005's coin branch; checkout already works as UC006 without it.
- Phase F (chat) is last — no upstream dependency, but it adds a new transport (WebSocket) and benefits from all other patterns being settled.

## 3. Phase A — Foundation & security (detailed)

### 3a. Data model changes

Extend the `user` table (additive — no breaking changes to existing columns):

```
ALTER TABLE user
  ADD COLUMN phone          VARCHAR(20)   UNIQUE,          -- UC001/UC002/UC017
  ADD COLUMN nickname       VARCHAR(64),                    -- UC016 (null → defaults to username)
  ADD COLUMN avatar_url     VARCHAR(512),                   -- UC016
  ADD COLUMN signature      VARCHAR(256),                   -- UC016 (null → defaults to "—— · ——")
  ADD COLUMN status         VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / LOCKED (UC018)
  ADD COLUMN failed_attempts INT          NOT NULL DEFAULT 0;          -- UC018 counter
```

New tables:

```
-- operation log (NFR: 操作日志记录) — AOP-written, one row per mutating endpoint
CREATE TABLE operation_log (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT,                 -- null for anonymous
  username     VARCHAR(64),
  method       VARCHAR(10),            -- HTTP method
  uri          VARCHAR(256),
  params       TEXT,                   -- request body/params (truncated, secrets masked)
  result_status VARCHAR(16),           -- SUCCESS / FAIL
  cost_ms      INT,
  ip           VARCHAR(64),
  create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_oplog_user (user_id),
  INDEX idx_oplog_time (create_time)
);
```

Verification codes and login-attempt counters live in **Redis** (ephemeral, not DB):
- `sms:code:{purpose}:{phone}` → 6-digit code, TTL 5 min. One code per phone+purpose.
- `sms:cooldown:{purpose}:{phone}` → "1", TTL 60s. Enforces "60秒后可重新获取验证码".
- `login:fail:{userId}` → integer counter, TTL 30 min (sliding). Reaches 10 → set `user.status='LOCKED'`.
- Lockout release: password reset via SMS unlocks + clears counter + resets `status`.

`purpose` enum: `REGISTER`, `FORGOT_PASSWORD`, `REBIND_OLD`, `REBIND_NEW`, `LOCKOUT_RESET`.

### 3b. SMS sender (the swappable interface)

```
public interface SmsSender {
    void sendVerificationCode(String phone, String code, String purpose);
}

// dev/default — writes code to the log + a file so flows work with no provider configured
@Profile("!prod")  // or always-active default
public class LoggingSmsSender implements SmsSender { ... log.info("[SMS] {} -> {} code={}", purpose, phone, code) ... }

// Aliyun dysmsapi — DECIDED provider. Via aliyun-java-sdk-dysmsapi (or dysmsapi20170525).
// Requires an approved 短信签名 + 短信模板 (user action, 1-2 biz days approval).
@ConditionalOnProperty(name="frame.sms.provider", havingValue="aliyun")
public class AliyunSmsSender implements SmsSender { ... }

// Chuanglan — kept as an alternate impl behind the interface (does not serve individuals;
// here only so the swappable-interface design is demonstrated). Not used unless re-enabled.
@ConditionalOnProperty(name="frame.sms.provider", havingValue="chuanglan")
public class ChuanglanSmsSender implements SmsSender { ... }
```

Config (`application.yml`):
```yaml
frame.sms.provider: logging          # logging | aliyun | chuanglan
frame.sms.aliyun.access-key-id: ...
frame.sms.aliyun.access-key-secret: ...
frame.sms.aliyun.sign-name: ...       # approved 短信签名
frame.sms.aliyun.template-code: ...   # approved verification-code 短信模板
```

**Provider: Aliyun SMS (dysmsapi) — DECIDED.** Chuanglan was the original recommendation but does not provide service to individual accounts; Aliyun does. Aliyun requires an approved SMS signature (短信签名) + a verification-code template (短信模板) — approval typically takes 1–2 business days, so the user should register/create these early (in parallel with the early Phase A work) since it gates live SMS. Aliyun's SDK (`aliyun-java-sdk-core` + `aliyun-java-sdk-dysmsapi`, or the newer `dysmsapi20170525`) sends a code via a single API call. Both providers are wired behind the `SmsSender` interface, so switching later is a one-line config change. The `LoggingSmsSender` dev stub means all SMS flows work locally before the Aliyun signature/template is approved — and per the scope decision, no automated tests exercise SMS; the user verifies the real provider manually. **Action for the user (do early, in parallel):** create an Aliyun account, apply for a 短信签名 + a verification-code 短信模板; both need approval before live SMS works.

### 3c. Verification-code service

```
VerificationCodeService {
  void     sendCode(phone, purpose)         // 60s cooldown check → gen 6-digit → store Redis TTL 5m → SmsSender.send
  boolean  verifyAndConsume(phone, code, purpose)  // match + DEL (one-shot); mismatch → false (do NOT reveal which)
}
```

Captcha (人机验证, UC001 "获取时人机验证"): **in-house SVG captcha — DECIDED** (no third-party). A server-generated simple math/text challenge (`captcha:{id}` in Redis, TTL 2 min) rendered as an inline SVG in the frontend, verified before `sendCode`. Self-contained, no reCAPTCHA/TencentCaptcha dependency. The captcha gate is on the `sendCode` endpoint only (register + forgot-password + rebind).

### 3d. Endpoints (Phase A)

| Method | Path | Auth | Purpose | UC |
|---|---|---|---|---|
| POST | `/auth/register` | public (modified) | now takes username + password + phone + code (+ agreed checkbox); validates username 4-25 chars no specials, password ≥8 with ≥2 of {upper,lower,special}, code verified, banned-word check | UC001 |
| POST | `/auth/login` | public (modified) | login by username OR phone + password; lockout logic | UC002 |
| GET | `/captcha` | public | returns `{captchaId, svgImage}` for 人机验证 | UC001 |
| POST | `/auth/sms/send` | public | body: `{phone, purpose, captchaId, captchaAnswer}` → sends code (60s cooldown) | UC001/015/017/018 |
| POST | `/auth/password/forgot` | public | body: `{phone, code, newPassword}` → verify code + reset (also unlocks) | UC015 |
| POST | `/auth/password/change` | USER | body: `{oldPassword, newPassword}` → verify old + set new | UC015 |
| GET | `/auth/me` | USER (modified) | returns full profile (phone, nickname, avatar, signature) | UC016 |
| PUT | `/auth/profile` | USER | body: `{nickname, signature}` → banned-word check | UC016 |
| POST | `/auth/avatar` | USER | multipart → upload avatar image | UC016 |
| POST | `/auth/phone/rebind/init` | USER | body: `{oldPhoneUsable: bool}` → starts rebind flow | UC017 |
| POST | `/auth/phone/rebind/verify-old` | USER | body: `{code}` (if oldPhoneUsable) OR `{password}` (if not) | UC017 |
| POST | `/auth/phone/rebind/confirm` | USER | body: `{newPhone, code}` → verifies code sent to new phone, updates phone | UC017 |

Username/password validation rules (UC001, enforced server-side in `RegisterRequest`/validator):
- Username: 4–25 chars, alphanumeric only (no special chars); unique; banned-word filter (a `banned_words.txt` resource or a small list — profanity/ads).
- Password: ≥8 chars, contains at least 2 of {uppercase, lowercase, special char}. (Digit not required by the spec; "任意两项" = any two of the three categories.)

Login lockout (UC018): on each failed login, `INCR login:fail:{userId}` (TTL 30m). At 10 → `UPDATE user SET status='LOCKED'`. A locked account cannot login (return a "locked, use forgot-password" error). Forgot-password via SMS resets the password AND clears `status` to ACTIVE + deletes the fail counter.

### 3e. HTTPS (NFR: HTTPS全站加密)

Dev: Vite dev server already serves HTTPS optionally (`server.https` + a cert). For the backend, Phase A adds a self-signed cert via `keytool` (the JDK `bin` is now on PATH) into a keystore, and `server.ssl.*` in `application.yml` (dev only, self-signed). The browser will warn on self-signed — acceptable for dev. Prod: the live VM already terminates TLS at Nginx (or will be configured to); backend stays HTTP behind Nginx. The "全站加密" requirement is met by Nginx HTTPS in prod + HTTPS in dev.

### 3f. Operation logging (NFR: 操作日志记录)

An AOP aspect `@OperationLog` (or a `HandlerInterceptor` on mutating methods) writes to `operation_log` for every POST/PUT/DELETE. Captures: userId (from SecurityContext), username, method, URI, params (JSON-serialized, truncated to ~2KB, password/phone/code fields masked), result status, cost ms, IP. Sensitive fields (`password`, `code`, `oldPassword`, `newPassword`) are masked in the params blob. An admin endpoint `GET /admin/operation-logs` (paginated, filterable by user/time) exposes it. Anti-SQL-injection (NFR: 防SQL注入) is already covered by MyBatis-Plus parameterized queries — no string-concatenated SQL anywhere; Phase A audits this and adds a note.

## 4. Phase B — Catalog (detailed)

UC000: search by name (fuzzy, multi-keyword split on comma/space, half-match), sort by price/sales/rating asc+desc, special-char-safe (no crash), "暂无数据" when empty.

- `GET /products` extended: add query params `keyword`, `sortBy` (`price`/`sales`/`rating`), `sortOrder` (`asc`/`desc`), keep `category`. `keyword` is split on `[,\s]+` into N terms; each term becomes `name LIKE %term%` ANDed together (parameterized — SQL-injection-safe). Special chars: terms are used as bind parameters, so `<`, `'`, etc. are literal and never crash.
- `sales` sort: `SUM(quantity)` over `order_item` joined to `orders` where status in (PAID, SHIPPED, DELIVERED, COMPLETED). Computed via a join/subquery, not a denormalized column (kept consistent). (Performance: add an index on `order_item(product_id)` — already exists.)
- `rating` sort: `AVG(stars)` over `review` for the product. Returns 0/NULL when no reviews → sorts last in desc. Phase B uses this with 0 reviews initially; Phase D populates it.
- "暂无数据" is a frontend concern (empty result → show the message).
- New frontend: `ProductDetailPage` at `/products/:id` (currently no detail page exists — `getProduct(id)` is used only for live-stock polling). Shows full description, images, stock, reviews (Phase D plugs in).

## 5. Phase C — Order lifecycle & refunds (detailed)

### 5a. Extended order state machine

Current: `PENDING / PAID / CANCELLED / FAILED`. Extended to:
```
PENDING (unpaid, 30-min expiry) 
  → PAID 
    → SHIPPED (admin sets tracking) 
      → DELIVERED (admin marks, or user signs 收货) 
        → COMPLETED (after review, or auto after N days)
  → CANCELLED (user cancels unpaid — UC007)
PAID → CANCELLED + REFUNDED (user cancels pre-ship — UC008, auto-refund)
SHIPPED → REFUND_PENDING (user cancels post-ship — UC009, admin approval)
  → REFUNDED (admin approves) | back to SHIPPED (admin denies)
```
Add `REFUND_PENDING`, `SHIPPED`, `DELIVERED`, `COMPLETED`, `REFUNDED` to the `OrderStatus` enum. Add columns:
```
ALTER TABLE orders
  ADD COLUMN tracking_number  VARCHAR(64),   -- set by admin on ship
  ADD COLUMN shipped_at       DATETIME,
  ADD COLUMN delivered_at     DATETIME,
  ADD COLUMN refund_status    VARCHAR(16),    -- NONE / PENDING / APPROVED / DENIED
  ADD COLUMN refund_reason    VARCHAR(256),
  ADD COLUMN paid_at          DATETIME;
```

### 5b. 30-min unpaid expiry (UC004)

A `@Scheduled` task (every 60s) finds `PENDING` orders older than 30 min → `CANCELLED` (frees any reserved stock — the flash-sale one-per-user key `DEL`'d so the user can retry). This matches "系统保存下单信息30mins，之后清空".

### 5c. Cancel + refund flows

- UC007 (cancel unpaid): `PENDING → CANCELLED` (exists; keep).
- UC008 (cancel paid, not shipped): `PAID → CANCELLED` + `refund_status=APPROVED` + mock refund success (the "默认退款成功"). One-per-user key `DEL`'d.
- UC009 (cancel after shipped): `SHIPPED → REFUND_PENDING` (user request) → admin approves → `REFUNDED` (or denies → back to `SHIPPED`). The "管理员批准退款后" step.

### 5d. Admin order management (UC014)

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/admin/orders` | ADMIN | paginated, filterable by status |
| POST | `/admin/orders/{id}/ship` | ADMIN | body: `{trackingNumber}` → PAID→SHIPPED |
| POST | `/admin/orders/{id}/deliver` | ADMIN | SHIPPED→DELIVERED (mark signed) |
| GET | `/admin/refunds` | ADMIN | list REFUND_PENDING orders |
| POST | `/admin/refunds/{orderId}/approve` | ADMIN | REFUND_PENDING→REFUNDED |
| POST | `/admin/refunds/{orderId}/deny` | ADMIN | REFUND_PENDING→SHIPPED |

Refund is mock (默认退款成功 / 默认退款成功) per the use cases — no real payment gateway. Coins used on a refunded order (Phase E) are returned to the ledger.

## 6. Phase D — Reviews (detailed)

UC010: after signing (DELIVERED), user reviews 3 dimensions — 商品质量 (quality), 物流速度 (logistics), 客服态度 (service) — each 1–5 stars. Optional text + images (jpg/png ≤20MB). Can edit after submitting.

```
CREATE TABLE review (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id      BIGINT NOT NULL,
  user_id       BIGINT NOT NULL,
  product_id    BIGINT NOT NULL,
  quality_stars TINYINT NOT NULL,       -- 1-5
  logistics_stars TINYINT NOT NULL,
  service_stars TINYINT NOT NULL,
  content       TEXT,
  create_time   DATETIME,
  update_time   DATETIME,
  UNIQUE KEY uk_review_order (order_id),  -- one review per order
  INDEX idx_review_product (product_id)
);
CREATE TABLE review_image (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  review_id   BIGINT NOT NULL,
  image_url   VARCHAR(512) NOT NULL,
  INDEX idx_reviewimg_review (review_id)
);
```

- Product avg rating = `AVG((quality+logistics+service)/3.0)` over reviews for that product → feeds Phase B's rating sort.
- Image upload: a new `POST /reviews/{id}/images` (multipart, jpg/png, ≤20MB per the spec — note the current multipart max is 10MB, so raise `spring.servlet.multipart.max-file-size` to 20MB+). Store under the configurable upload dir.
- Edit: `PUT /reviews/{id}` (owner only) — allowed anytime per "系统允许评价结束后再修改评价".
- Endpoints: `POST /reviews` (create, requires DELIVERED order owned by user), `GET /products/{id}/reviews` (public, paginated), `PUT /reviews/{id}` (owner), `POST /reviews/{id}/images` (owner).
- Order moves to `COMPLETED` after review (or auto after N days — N configurable, default 7).
- Frontend: review form with 3 star-pickers (click a star 1-5), text area, image uploader; review list on `ProductDetailPage`.

## 7. Phase E — Coins economy (detailed)

UC012: daily-login reward (100/day, 4am CST refresh), review reward (= actual paid amount), 30-day per-credit FIFO expiry, checkout discount rules.

```
CREATE TABLE coin_ledger (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT NOT NULL,
  amount       INT NOT NULL,          -- +earned / -spent (positive/negative coins)
  balance_after INT NOT NULL,         -- running balance snapshot
  source       VARCHAR(32) NOT NULL,  -- DAILY_LOGIN / REVIEW_REWARD / CHECKOUT_SPEND / REFUND_RETURN / EXPIRED
  ref_order_id BIGINT,                -- order that earned/spent the coins
  expires_at   DATETIME NOT NULL,     -- earned credits expire 30 days out; spent/refund rows null
  create_time  DATETIME,
  INDEX idx_coin_user (user_id),
  INDEX idx_coin_expire (expires_at)
);
```

- **Balance** = `SUM(amount)` over non-expired rows for the user (or a cached `coin_balance:{userId}` Redis key invalidated on ledger change). Expiry: a `@Scheduled` task (daily at 4:01am CST) marks credits past `expires_at` as expired (a balancing `EXPIRED` row) — "根据获取时间独立计算" = each credit row has its own 30-day clock.
- **Daily-login reward**: when a user logs in and hasn't earned a DAILY_LOGIN since 4am CST today → +100 coins, `expires_at = now + 30 days`. 4am CST = the `Asia/Shanghai` timezone (already the app timezone); the "refresh at 4am" is a date-boundary check (date in CST of last login vs now).
- **Review reward**: on review submission → +coins equal to the order's actual paid amount (in 元, i.e. `paidInCents/100` coins — "等同于本订单实际付款金额"). `ref_order_id` = the order.
- **Checkout discount** (UC005 coin branch): user chooses coins to spend; rule — 100 coins = 1 元; max discount = 50% of the *undiscounted* subtotal; **discount (flash-sale) products cannot use coins** (the per-item `isFlashSale` flag gates this). Applied at checkout: total = subtotal - min(coinsSpendable/100, floor(subtotalNonFlash * 0.5)). A `CHECKOUT_SPEND` ledger row deducts the coins; on refund, a `REFUND_RETURN` row credits them back.
- Endpoints: `GET /coins/balance` (USER), `GET /coins/history` (USER, paginated). Checkout `CheckoutRequest` gains optional `useCoins: bool` + `coinAmount: int`.

## 8. Phase F — Live chat, lazy-load, 10k QPS, cleanup (detailed)

- **Live chat (UC011)**: `spring-boot-starter-websocket` + STOMP (or raw WebSocket handler). Entities:
  ```
  CREATE TABLE chat_session (id, user_id, admin_id, status, create_time, ...);
  CREATE TABLE chat_message (id, session_id, sender_type USER/ADMIN, content, image_url, create_time, INDEX idx_msg_session);
  ```
  User ↔ admin real-time messaging. Messages persisted (后置条件: 聊天信息已存储). Frontend: a chat widget (socket.io-client not needed — native WebSocket or `@stomp/stompjs`).
- **Image lazy-load (NFR)**: frontend `loading="lazy"` on all product/review images + a placeholder; first-screen ≤1.5s verified by check.
- **10k QPS (NFR)**: a load test (k6 or a Node script reusing the e2e http client) hitting `GET /products` (Redis-cached, the hottest read path) at 10k req/s, measuring p95 < 2s. Document the result. The existing architecture (Redis Lua gate, Redis-cached reads, stateless JWT, HikariCP 50, Nginx rate-limit) is the design that makes this feasible; Phase F produces the proof artifact. If the single-VM can't hit 10k QPS, document the bottleneck and the two-host distributed variant as the scaling path (already the planned next milestone).
- **Skipped-bug cleanup**: fix everything logged in `docs/superpowers/skipped-issues.md` during earlier phases.
- **Final regression**: re-run the full e2e suite (now extended to cover new UCs) + backend tests + redeploy to the VM.

## 9. Testing strategy

- **Backend**: JUnit + Mockito unit tests for each new service's logic (verification-code cooldown/verify, lockout counter, coins FIFO expiry/discount calc, refund state transitions, review avg). Integration tests (`@SpringBootTest` + real Redis/MariaDB) for the order state machine and checkout-with-coins. **No SMS tests** (per scope decision — `SmsSender` is mocked in unit tests, real provider verified manually by the user).
- **Frontend**: Vitest + Testing Library for new component logic (star picker, coin-discount calc, search/sort wiring, chat message list).
- **E2E**: extend the existing `e2e/` Node suite with new flows for the new UCs (register-with-code via `LoggingSmsSender`, order lifecycle to shipped/reviewed, review creation, coins, profile). The existing 26 tests stay as a regression baseline.
- **Skip-and-return**: a failed test with a hard bug → log to `docs/superpowers/skipped-issues.md` (test name, error, hypothesis), mark the test `@Disabled`/`test.skip` with a reason, move on. Fix at Phase F.

## 10. Deferred / not in this version

- **OAuth2.0** — explicitly deferred to save time. Existing JWT retained. The "安全性: 采用OAuth2.0认证机制" NFR is unmet in this version; noted in the final NFR matrix as "deferred".
- **Real payment gateway** — mock payment/refund (默认成功) per the use cases.
- **Real captcha** — **in-house SVG captcha used** (decided), not a third-party.
- **Two-host distributed deployment** — still the next milestone after this; Phase F's 10k QPS test points at it as the scaling path if the single VM falls short.

## 11. Traceability matrix (UC → phase → status)

| UC | Feature | Phase | Pre-existing status | After this phase |
|---|---|---|---|---|
| UC000 | search/sort products | B | partial (pagination only) | full |
| UC001 | SMS-gated register | A | missing | full |
| UC002 | login (username or phone) | A | exists (username only) | full |
| UC003 | cart | (none) | exists (client-side) | unchanged |
| UC004 | order unpaid + 30min expiry | C | partial (no expiry) | full |
| UC005/006 | order + pay (merged, coins optional) | C+E | partial (pay, no coins) | full |
| UC007 | cancel unpaid | (none) | exists | unchanged |
| UC008 | cancel paid pre-ship + refund | C | missing | full |
| UC009 | cancel post-ship + admin refund | C | missing | full |
| UC010 | reviews (3-D stars + images + edit) | D | missing | full |
| UC011 | live chat | F | missing | full |
| UC012 | coins economy | E | missing | full |
| UC013 | admin stock/price/discount mgmt | B/C | partial (CRUD exists) | full |
| UC014 | admin shipping/refund mgmt | C | missing | full |
| UC015 | password reset + change | A | missing | full |
| UC016 | profile (nickname/avatar/signature) | A | missing | full |
| UC017 | phone rebind | A | missing | full |
| UC018 | account lockout (10 wrong) | A | missing | full |
| NFR perf | 10000 QPS, <2s, lazy-load, ≤1.5s | F | partial (architecture) | proof artifact |
| NFR avail | 99.95% | — | (operational, not built) | documented |
| NFR security | OAuth2/HTTPS/防SQL注入/op-log | A | missing | HTTPS+op-log+防注入 built; **OAuth2 deferred** |
| NFR compat | Chrome/Firefox/Edge | — | (frontend standards) | met by using standard React/Tailwind |
| NFR maintain | hot-deploy ≤5min, recovery <10min | — | (operational) | documented |

## 12. Build order summary

0. Merge `e2e-test-suite`→`main`; re-run baseline tests.
1. **Phase A** — Foundation & security (User fields, SMS, verification, lockout, password reset, phone rebind, profile, op-log, HTTPS). *No OAuth2.*
2. **Phase B** — Catalog (search, sort, detail page).
3. **Phase C** — Order lifecycle & refunds (states, expiry, cancel/refund, admin mgmt).
4. **Phase D** — Reviews (3-D stars, images, edit; feeds B's rating sort).
5. **Phase E** — Coins (daily-login, review reward, FIFO expiry, checkout discount; completes UC005).
6. **Phase F** — Live chat (WebSocket), lazy-load, 10k QPS proof, skipped-bug cleanup, final regression + redeploy.

Each phase: spec section above → implementation plan (writing-plans skill) → build → test → merge to `main` → re-run e2e regression.
