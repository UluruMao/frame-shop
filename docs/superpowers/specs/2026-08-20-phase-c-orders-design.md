# Design — Frame Shop Phase C (Order Lifecycle & Refunds): UC004/005/006/007/008/009/014

> Date: 2026-08-20. Phase C extends the order lifecycle from `PENDING/PAID/CANCELLED/FAILED` to a full e-commerce state machine: shipped, delivered, completed, refund-requested, refunded. Adds the 30-min unpaid expiry, cancel+refund flows (incl. post-ship admin approval), user confirm-receipt, and admin order/shipping/refund management. Built on `main` (HEAD `62cc098`, Phase B merged) on a new `feature/phase-c-orders` branch.
> Covers UC004, UC005/006 (merged), UC007, UC008, UC009, UC014 of `use case/use cases.txt`. UC010 (reviews) is Phase D; `COMPLETED` is added to the enum here + an auto-complete scheduler, but review-triggered completion lands in Phase D.
> This phase sits under the umbrella design `2026-08-19-frame-shop-uc-fulfillment-design.md` §5; this document is the detailed Phase C spec.

## 1. Scope (locked)

- **Extended order state machine** — add `SHIPPED`, `DELIVERED`, `COMPLETED`, `REFUND_PENDING`, `REFUNDED` to `OrderStatus` (keeping `PENDING`/`PAID`/`CANCELLED`/`FAILED`).
- **UC004 — 30-min unpaid expiry**: `@Scheduled` task (every 60s) finds `PENDING` orders older than 30 min → `CANCELLED` + stock recredit + `seckill:` DEL.
- **UC007 — cancel unpaid**: `PENDING → CANCELLED` (exists; extend to recredit stock + DEL `seckill:`).
- **UC008 — cancel paid pre-ship**: `PAID → CANCELLED` + `refund_status=APPROVED` + mock refund (默认退款成功) + stock recredit + `seckill:` DEL.
- **UC009 — cancel post-ship**: `SHIPPED → REFUND_PENDING` (user request) → admin approves → `REFUNDED` (or denies → back to `SHIPPED`). Post-ship refund does NOT recredit stock until approved (the item is in transit).
- **UC014 — admin order management**: `GET /admin/orders` (paginated, filterable by status), `POST /admin/orders/{id}/ship` (PAID→SHIPPED, sets tracking), `POST /admin/orders/{id}/deliver` (SHIPPED→DELIVERED), `GET /admin/refunds` (list REFUND_PENDING), `POST /admin/refunds/{orderId}/approve` (REFUND_PENDING→REFUNDED + stock recredit), `POST /admin/refunds/{orderId}/deny` (REFUND_PENDING→SHIPPED).
- **UC004/005 checkout fields**: add `customerPhone` (收件手机号) + `paymentMethod` (微信/支付宝/银行卡) to the order + checkout (email retained for back-compat).
- **User confirm-receipt (签收)**: `POST /orders/{id}/confirm-receipt` (SHIPPED→DELIVERED, user-owned). The spec allows admin OR user to mark delivered.
- **COMPLETED + auto-complete scheduler**: `COMPLETED` added to enum; `@Scheduled` (daily) marks `DELIVERED` orders older than 7 days → `COMPLETED`. The review-triggered COMPLETED path lands in Phase D.
- **Frontend Orders page**: sub-classification tabs 全部/未付款/未发货/未签收/已完成 (5 of 6; "未评价" deferred to Phase D), status-param filtering, per-status actions (Pay / Cancel / Confirm-receipt / Request-refund), line-items rendering, tracking number display. Admin page gains an order-management section (ship / deliver / approve refunds).

### Out of scope (deferred)
- **Reviews (UC010)** + the "未评价" tab + review-triggered COMPLETED → Phase D.
- **Coins** (UC012) — refund coin-return → Phase E (a `REFUND_RETURN` ledger row then).
- **Real payment gateway** — mock refund (默认退款成功) per the use cases.

## 2. Decisions (this phase)

| Decision | Choice | Why |
|---|---|---|
| Stock compensation on cancel/refund | Add `releaseOrderStock(orderId)`: recredit Redis (`stockGateService.recredit`) + DB (`productMapper.recreditStock`) + DEL `seckill:{pid}:{userId}` per item | Current `cancel()` doesn't recredit stock or release the one-per-user lock — a real bug the exploration surfaced; the spec requires "DEL one-per-user key". Keeps stock consistent. Non-flash items: `seckill:` absent (no-op), Redis `stock:` absent (recredit no-op), DB recredit always done. |
| Refund model | UC008 = `PAID→CANCELLED` + `refund_status=APPROVED` (auto-refund on cancel); UC009 = `SHIPPED→REFUND_PENDING`→admin→`REFUNDED` | Matches spec §5c. A separate `refund_status` column tracks the refund lifecycle independently of `status` (so a CANCELLED order can carry APPROVED refund status). |
| Post-ship refund stock | Recredit stock only on admin APPROVE (not on REFUND_PENDING request) | The item is in transit until the refund is approved; stock returns when the refund is finalized. |
| `COMPLETED` transition | Enum value + auto-complete scheduler (DELIVERED >7d → COMPLETED); review-triggered COMPLETED in Phase D | Lets the "已完成" tab work in Phase C without reviews; Phase D adds the review path. |
| Checkout fields | Add `customerPhone` (收件手机号) + `paymentMethod` (WECHAT/ALIPAY/BANK_CARD) to Order + CheckoutRequest | UC004/005 explicitly require 收件手机号 + payment-method selection. Email retained (back-compat). |
| Confirm-receipt | `POST /orders/{id}/confirm-receipt` (USER, SHIPPED→DELIVERED) | Spec: "admin marks, OR user signs 收货". Cheap, lets the user drive the lifecycle. |
| `updateStatusIf` reuse | Reuse the existing CAS primitive (`update orders set status=#{to} where id=#{id} and status=#{from}`); add a richer `updateStatusAndColumns` only if a transition must set extra columns atomically | The existing single-column CAS is sufficient for most transitions; tracking_number/refund_status set via separate `updateById` after the CAS guards the transition. |
| Refund audit | Use the existing `OperationLog` AOP (Phase A) — refund approve/deny are mutating admin endpoints, auto-logged | No new refund-audit table; the operation_log already audits admin mutations. |
| Orders tabs | 全部/未付款/未发货/未签收/已完成 (5); "未评价" deferred to Phase D | "未评价" needs the review entity (Phase D) to know which DELIVERED/COMPLETED orders lack reviews. |
| E2E baseline | Branch off `main` (Phase B merged). Re-run backend (72) + frontend (27) + e2e catalog (7) as the local gate | Same per-phase-gate convention. The pre-existing non-catalog e2e failures (auth contract) remain deferred to Phase F. |

## 3. Data model

### 3a. `OrderStatus` enum — extend

```java
public enum OrderStatus {
    PENDING, PAID, CANCELLED, FAILED,
    SHIPPED, DELIVERED, COMPLETED,
    REFUND_PENDING, REFUNDED
}
```
`orders.status` is `VARCHAR(16)` — all names fit (longest = `REFUND_PENDING` = 14). No column widening needed.

### 3b. `orders` table — additive columns

```sql
ALTER TABLE orders
  ADD COLUMN customer_phone   VARCHAR(20),    -- 收件手机号 (UC004/005)
  ADD COLUMN payment_method    VARCHAR(16),    -- WECHAT / ALIPAY / BANK_CARD
  ADD COLUMN tracking_number   VARCHAR(64),    -- set by admin on ship
  ADD COLUMN shipped_at        DATETIME,
  ADD COLUMN delivered_at      DATETIME,
  ADD COLUMN completed_at      DATETIME,
  ADD COLUMN paid_at           DATETIME,
  ADD COLUMN refund_status    VARCHAR(16) NOT NULL DEFAULT 'NONE',  -- NONE / PENDING / APPROVED / DENIED
  ADD COLUMN refund_reason     VARCHAR(256),
  ADD COLUMN cancel_reason     VARCHAR(256);   -- user cancel reason (optional)
```
These go into `schema.sql` (additive to the `orders` CREATE TABLE) + an idempotent migration note (the live dev DB is altered inline, same as the `review` table in Phase B; `application.yml` has no `spring.sql.init.mode`).

New `RefundStatus` enum: `NONE, PENDING, APPROVED, DENIED`.
New `PaymentMethod` enum: `WECHAT, ALIPAY, BANK_CARD`.

### 3c. No new tables

Refund is modeled as `refund_status` + `refund_reason` columns on `orders` (no separate refund table) — matches the spec's "mock refund" simplicity and the single-row CAS model. No new entity beyond extending `Order`.

## 4. Backend

### 4a. State machine (transitions, all via CAS `updateStatusIf` unless noted)

```
PENDING  --pay-->          PAID                      (exists, PaymentService)
PENDING  --cancel-->       CANCELLED                 (UC007; +recredit stock, DEL seckill)
PENDING  --30min-->        CANCELLED                 (UC004 scheduler; +recredit stock, DEL seckill)
PAID     --admin ship-->   SHIPPED                   (UC014; +tracking_number, shipped_at)
PAID     --cancel-->       CANCELLED (refund APPROVED) (UC008; +recredit stock, DEL seckill; refund_status=APPROVED)
SHIPPED  --user confirm-->  DELIVERED                 (UC010 prereq; +delivered_at)
SHIPPED  --admin deliver--> DELIVERED                (UC014 alt; +delivered_at)
SHIPPED  --user cancel-->  REFUND_PENDING            (UC009; +refund_status=PENDING, refund_reason)
REFUND_PENDING --admin approve--> REFUNDED           (UC009; +refund_status=APPROVED, +recredit stock, DEL seckill)
REFUND_PENDING --admin deny-->   SHIPPED              (UC009; +refund_status=DENIED)
DELIVERED --7d scheduler--> COMPLETED                (+completed_at)
DELIVERED --review-->      COMPLETED                 (Phase D)
```

### 4b. `OrderService` — new methods

```java
void cancel(Long id, Long userId);              // UC007: extend to recredit stock + DEL seckill
void cancelPaid(Long id, Long userId);          // UC008: PAID→CANCELLED + refund APPROVED + recredit stock + DEL seckill
void requestRefund(Long id, Long userId, String reason);  // UC009: SHIPPED→REFUND_PENDING + refund_status=PENDING
void confirmReceipt(Long id, Long userId);     // SHIPPED→DELIVERED
// admin-side (in AdminOrderService, below)
```

`releaseOrderStock(Order order)` (private helper): for each `OrderItem` of the order, `stockGateService.recredit(productId, qty)` (Redis, flash-sale key no-op if absent) + `productMapper.recreditStock(productId, qty)` (DB) + `redis.delete("seckill:" + productId + ":" + order.getUserId())`. Wrapped so Redis failures don't block the DB transition (warn-log, like the existing `getById` cache pattern).

### 4c. `AdminOrderService` (new) — admin-side transitions

```java
PageResult<Order> adminListOrders(PageQuery q, OrderStatus status);  // filterable
void ship(Long id, String trackingNumber);     // PAID→SHIPPED + tracking_number + shipped_at
void deliver(Long id);                         // SHIPPED→DELIVERED + delivered_at
PageResult<Order> refundRequests(PageQuery q); // list REFUND_PENDING
void approveRefund(Long id);                    // REFUND_PENDING→REFUNDED + refund_status=APPROVED + recredit stock + DEL seckill
void denyRefund(Long id);                       // REFUND_PENDING→SHIPPED + refund_status=DENIED
```
Each admin transition uses the CAS guard (`updateStatusIf(id, from, to)`); on `rows==0` → `CONFLICT` ("order cannot transition from X"). The `OperationLog` AOP (Phase A) auto-audits these.

### 4d. Schedulers (new `OrderLifecycleScheduler`)

- `@Scheduled(fixedDelay = 60_000) void expirePendingOrders()` — finds `PENDING` orders with `create_time < now - 30 min` → for each: `updateStatusIf(id, PENDING, CANCELLED)`; on success, `releaseOrderStock`. (UC004.)
- `@Scheduled(cron = "0 7 4 * * *") void autoCompleteOrders()` — daily ~4am, finds `DELIVERED` orders with `delivered_at < now - 7 days` → `updateStatusIf(id, DELIVERED, COMPLETED)` + `completed_at`. (Configurable `frame.order.complete-after-days=7`.)

Both follow the existing scheduler pattern (method-level `@Scheduled` on a `@Service`/`@Component`, try/catch so the scheduler never dies, shared 4-thread pool). The cron for auto-complete is off-:00 (7 past) per the no-herd-thundering-flock guidance.

### 4e. `OrderMapper` — additions

```java
List<Order> selectPendingBefore(LocalDateTime cutoff);   // for the expiry scheduler
List<Order> selectDeliveredBefore(LocalDateTime cutoff); // for the auto-complete scheduler
int updateStatusAndTracking(Long id, OrderStatus from, OrderStatus to, String trackingNumber, LocalDateTime shippedAt);  // ship transition + tracking atomically
int updateStatusAndRefund(Long id, OrderStatus from, OrderStatus to, RefundStatus refundStatus, String reason);
```
Plus the existing `updateStatusIf` for the simpler transitions. The richer methods set the extra columns in the SAME UPDATE (atomic — the CAS guard + the column set in one statement, so a concurrent transition can't leave tracking set on a non-shipped order).

### 4f. Checkout — add phone + payment method

`CheckoutRequest` gains `customerPhone` (required) + `paymentMethod` (required). `CheckoutServiceImpl` passes them into the order payload; `StreamConsumerService.processPayload` sets them on the `Order` at insert. (`paymentMethod` is stored for record-keeping; payment is still mock.)

### 4g. Endpoints

User (`OrderController`, all USER-auth, ownership-checked):
| Method | Path | Purpose | UC |
|---|---|---|---|
| GET | `/orders/me?status=` | my orders, optional status filter (for tabs) | tabs |
| POST | `/orders/{id}/cancel` | cancel unpaid (PENDING→CANCELLED) | UC007 |
| POST | `/orders/{id}/cancel-paid` | cancel paid pre-ship (PAID→CANCELLED+refund) | UC008 |
| POST | `/orders/{id}/refund-request` | request refund post-ship (SHIPPED→REFUND_PENDING) | UC009 |
| POST | `/orders/{id}/confirm-receipt` | confirm receipt (SHIPPED→DELIVERED) | UC010 prereq |
| GET | `/orders/{id}/items` | line items for the order detail view | detail |

Admin (`AdminOrderController`, new, all ADMIN-auth):
| Method | Path | Purpose | UC |
|---|---|---|---|
| GET | `/admin/orders?status=` | paginated, filterable | UC014 |
| POST | `/admin/orders/{id}/ship` | body `{trackingNumber}` → SHIPPED | UC014 |
| POST | `/admin/orders/{id}/deliver` | SHIPPED→DELIVERED | UC014 |
| GET | `/admin/refunds` | list REFUND_PENDING | UC014 |
| POST | `/admin/refunds/{orderId}/approve` | REFUND_PENDING→REFUNDED | UC009/014 |
| POST | `/admin/refunds/{orderId}/deny` | REFUND_PENDING→SHIPPED | UC009/014 |

`SecurityConfig`: add `GET /admin/orders`, `POST /admin/orders/{id}/ship`, `POST /admin/orders/{id}/deliver`, `GET /admin/refunds`, `POST /admin/refunds/{orderId}/approve`, `POST /admin/refunds/{orderId}/deny` → `hasRole("ADMIN")`. `GET /orders/me` and the new `/orders/{id}/*` user endpoints stay under `anyRequest().authenticated()`.

### 4h. Phase B sales-sort IN-list widening

Per the Phase B status note, Phase C widens the `pageProducts` sold-aggregation `WHERE o.status = 'PAID'` to `WHERE o.status IN ('PAID','SHIPPED','DELIVERED','COMPLETED')` (REFUNDED excluded — refunded items aren't "sold"). One XML change in `ProductMapper.xml`.

## 5. Frontend

### 5a. Types (`src/api/types.ts`)
- Extend `OrderStatus` union with `SHIPPED | DELIVERED | COMPLETED | REFUND_PENDING | REFUNDED`.
- Extend `Order` with `customerPhone?`, `paymentMethod?`, `trackingNumber?`, `shippedAt?`, `deliveredAt?`, `completedAt?`, `paidAt?`, `refundStatus?`, `refundReason?`, `items?: OrderItem[]`.
- Extend `CheckoutRequest` with `customerPhone`, `paymentMethod`.
- Add `RefundStatus = 'NONE'|'PENDING'|'APPROVED'|'DENIED'`, `PaymentMethod = 'WECHAT'|'ALIPAY'|'BANK_CARD'`.

### 5b. API client (`src/api/order.ts`)
- `getMyOrders(current, size, status?)` — add optional status param.
- Add: `cancelPaid(id)`, `requestRefund(id, reason)`, `confirmReceipt(id)`, `getOrderItems(id)`.
- Add admin: `adminListOrders(status?, current?, size?)`, `adminShip(id, trackingNumber)`, `adminDeliver(id)`, `adminRefundRequests()`, `adminApproveRefund(orderId)`, `adminDenyRefund(orderId)`.

### 5c. Orders page (`src/pages/Orders.tsx`) — tabs + actions + line items
- Sub-classification tabs: 全部 / 未付款 / 未发货 / 未签收 / 已完成 (5 tabs; "未评价" deferred to Phase D — render a disabled "未评价 (Phase D)" tab).
  - 未付款 = PENDING; 未发货 = PAID; 未签收 = SHIPPED; 已完成 = COMPLETED; 全部 = all.
- Per-status actions: PENDING → Pay + Cancel; PAID → Cancel (refund); SHIPPED → Confirm-receipt + Request-refund; DELIVERED → (none yet); REFUND_PENDING → show "退款处理中"; COMPLETED → (none).
- Line items: expandable row or a detail drawer showing `getOrderItems(id)` (name, qty, price) + tracking number if SHIPPED+.
- Refund-request modal (reason text) for SHIPPED.

### 5d. Checkout page (`src/pages/Checkout.tsx`) — phone + payment method
- Add `customerPhone` field (required) + a payment-method radio (微信/支付宝/银行卡). Pass both in the `CheckoutRequest`. Keep the queued→pending→paid polling as-is.

### 5e. Admin page (`src/pages/Admin.tsx`) — order management section
- A new tab/section "Orders" listing orders (filterable by status) with admin actions: Ship (PAID→SHIPPED, tracking-number input), Deliver (SHIPPED→DELIVERED), and a Refunds sub-section listing REFUND_PENDING orders with Approve/Deny buttons. Reuse the existing client-side `useIsAdmin()` gate.

### 5f. Router
- No new top-level routes required (Orders + Admin already exist). Possibly a `/orders/:id` detail route if the line-items drawer isn't sufficient — prefer the drawer to minimize new routes. (Decision: drawer, no new route, unless the detail proves to need its own page.)

## 6. Testing

### 6a. Backend
- **Unit (`OrderServiceImplTest` etc.)**: cancel recredits stock + DELs seckill; cancelPaid sets refund APPROVED + recredits; requestRefund SHIPPED→REFUND_PENDING; confirmReceipt; admin ship/deliver/approve/deny transitions; CAS `rows==0` → CONFLICT on illegal transitions.
- **Integration (`OrderLifecycleIntegrationTest`, `@SpringBootTest` + real MariaDB + Redis)**: the full lifecycle PENDING→PAID→SHIPPED→DELIVERED→COMPLETED; UC007 cancel recredits stock; UC008 cancel-paid auto-refunds; UC009 post-ship refund (request→approve→REFUNDED, stock recredited only on approve; deny→back to SHIPPED); 30-min expiry scheduler cancels old PENDING; auto-complete scheduler completes old DELIVERED; concurrent cancel race (two cancels, one wins via CAS).
- **Scheduler tests**: the `@Scheduled` logic tested via direct service-method calls (not waiting for cron) — seed an old PENDING, call `expirePendingOrders()`, assert CANCELLED + stock recredited.

### 6b. Frontend
- `Orders.test.tsx` (new): tab filtering changes the status param; per-status actions render correctly (Pay/Cancel on PENDING, Confirm-receipt on SHIPPED, etc.); refund-request modal; line-items drawer.
- `Checkout.test.tsx` (extend): phone + payment-method fields required + submitted.
- `Admin` order section test (extend): ship action calls `adminShip`; refund approve/deny.

### 6c. E2E
- New `e2e/tests/orders.test.mjs` (or extend `checkout.test.mjs`): the full lifecycle as a user+admin — checkout→pay→admin ship→user confirm-receipt→(auto-complete or leave DELIVERED); cancel unpaid (UC007); cancel paid pre-ship (UC008); post-ship refund request + admin approve (UC009). Runs against local Phase C backend.
- Re-run catalog e2e (7) + backend (72) + frontend (27+) as the regression gate.

### 6d. Skip-and-return
Hard bugs → log to `skipped-issues.md`, `@Disabled`/`test.skip` with reason, proceed; fix at Phase F.

## 7. Error handling & edge cases

| Case | Behavior |
|---|---|
| Cancel a non-PENDING/non-PAID order | CAS `rows==0` → 409 CONFLICT ("order cannot be cancelled in current state") |
| Cancel a SHIPPED order via `/cancel` | 409 (use `/refund-request` instead) — the endpoint validates `from=PENDING` for `/cancel`, `from=PAID` for `/cancel-paid` |
| Request refund on a non-SHIPPED order | 409 |
| Approve refund on a non-REFUND_PENDING order | 409 |
| Ship a non-PAID order | 409 |
| Confirm receipt on a non-SHIPPED order | 409 |
| Concurrent cancels (race) | CAS: exactly one wins (PENDING→CANCELLED), the other gets 409 |
| 30-min expiry races a user cancel | CAS: whichever runs first wins; the loser no-ops (status already CANCELLED) |
| Stock recredit when Redis `stock:` key absent (non-flash) | `recredit` is a no-op on absent keys; DB `recreditStock` always runs |
| Refund coin return (Phase E) | N/A this phase; Phase E adds the `REFUND_RETURN` ledger row |

## 8. Build order (subagent-driven)

0. Branch `feature/phase-c-orders` off `main`; re-run baseline (backend 72 + frontend 27 + catalog e2e 7).
1. Backend: `OrderStatus` + `RefundStatus` + `PaymentMethod` enums; `orders` table columns; `Order` entity fields; `OrderMapper` additions + XML; `releaseOrderStock` helper; `OrderService` cancel/cancelPaid/requestRefund/confirmReceipt; `AdminOrderService`; `OrderLifecycleScheduler`; checkout phone+paymentMethod; controller endpoints; `SecurityConfig`. Tests (unit + integration).
2. Backend: widen `pageProducts` sales-sort IN-list (`PAID` → `PAID,SHIPPED,DELIVERED,COMPLETED`). Update the Phase B `ProductMapperIntegrationTest` expectation if needed.
3. Frontend: types; API client; Orders page tabs+actions+drawer; Checkout phone+paymentMethod; Admin order section; tests.
4. E2E: orders lifecycle flow + regression; final review; merge `feature/phase-c-orders`→`main`; re-run regression; update status doc + memory.

## 9. Traceability

| UC / NFR | Phase C coverage | Status after Phase C |
|---|---|---|
| UC004 30-min unpaid expiry | expirePendingOrders scheduler + cancel | full |
| UC005/006 pay (no coins) | exists; +paymentMethod recorded | full (coins = Phase E) |
| UC007 cancel unpaid | cancel() + stock recredit + seckill DEL | full |
| UC008 cancel paid pre-ship + refund | cancelPaid() + refund APPROVED + stock recredit | full (mock refund) |
| UC009 cancel post-ship + admin refund | requestRefund + admin approve/deny + stock recredit on approve | full (mock refund) |
| UC014 admin shipping/refund mgmt | AdminOrderController + Admin order section | full |
| (UC010 reviews) | COMPLETED enum + auto-complete scheduler; review-triggered COMPLETED = Phase D | partial |
| NFR op-log | refund approve/deny auto-audited via existing AOP | met |
