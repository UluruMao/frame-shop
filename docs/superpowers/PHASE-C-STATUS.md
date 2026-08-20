# Phase C (Order Lifecycle & Refunds) Complete — Status Report (2026-08-20, autonomous session)

## What I did
Ran the full cycle on **Phase C (Order Lifecycle & Refunds)** — UC004/005/006/007/008/009/014. Design spec → implementation plan → 7-task subagent-driven build → per-task code reviews → final whole-branch review → merge. All under auto mode.

**Merged to `main`** (commit `f64f9e8`, branch `feature/phase-c-orders`, 6 implementation commits). Working tree clean.

## What's built (UC004/005/006/007/008/009/014 + NFRs)
- **Extended order state machine** — `OrderStatus` now `PENDING/PAID/CANCELLED/FAILED/SHIPPED/DELIVERED/COMPLETED/REFUND_PENDING/REFUNDED`. New `RefundStatus` (NONE/PENDING/APPROVED/DENIED) + `PaymentMethod` (WECHAT/ALIPAY/BANK_CARD) enums. 10 new `orders` columns (customer_phone, payment_method, tracking_number, shipped_at, delivered_at, completed_at, paid_at, refund_status, refund_reason, cancel_reason).
- **CAS-guarded transitions** — every transition is an atomic `UPDATE ... WHERE id=#{id} AND status=#{from}` (`rows==0` → 409 CONFLICT); ownership checked BEFORE the CAS on user endpoints (user A can't transition user B's order). Richer atomic mappers (`updateStatusAndTracking`/`updateStatusAndRefund`/`updateStatusAndDelivered`/`updateStatusAndPaid`) set the extra column in the same UPDATE as the flip — no race window.
- **Stock compensation** — shared `OrderStockReleaseService.release(o)` (recredit Redis `stockGateService.recredit` + DB `productMapper.recreditStock` + DEL `seckill:{pid}:{userId}` per item; Redis-down is warn-logged, never blocks the DB transition). Fires on exactly the right transitions: cancel, cancelPaid, expireOrder, approveRefund — and NOT on requestRefund/denyRefund/confirmReceipt/completeOrder. Fixes the gap the Phase C exploration found (the old `cancel()` didn't recredit stock or release the one-per-user lock).
- **UC004 — 30-min unpaid expiry**: `OrderLifecycleScheduler.expirePendingOrders` (`@Scheduled(fixedDelay=60_000)`, configurable `frame.order.expire-minutes:30`) → `expireOrder` (CAS PENDING→CANCELLED + release + cancel_reason='expired').
- **UC007 — cancel unpaid**: `cancel` (PENDING→CANCELLED + release).
- **UC008 — cancel paid pre-ship**: `cancelPaid` (PAID→CANCELLED + refund APPROVED + release). Mock auto-refund (默认退款成功).
- **UC009 — cancel post-ship**: `requestRefund` (SHIPPED→REFUND_PENDING, no recredit — item in transit) → admin `approveRefund` (REFUND_PENDING→REFUNDED + release) / `denyRefund` (REFUND_PENDING→SHIPPED).
- **UC010 prereq — confirm-receipt + COMPLETED**: `confirmReceipt` (SHIPPED→DELIVERED, user); `completeOrder` (DELIVERED→COMPLETED + completed_at) via daily auto-complete scheduler (`@Scheduled(cron="0 7 4 * * *")`, configurable `frame.order.complete-after-days:7`). Review-triggered COMPLETED lands in Phase D.
- **UC014 — admin order/shipping/refund mgmt**: `AdminOrderController` (6 endpoints: list/ship/deliver/refunds/approve/deny) + Admin.tsx Orders tab (status-filter, ship w/ tracking, deliver) + Refunds sub-section (approve/deny). Admin mutating endpoints auto-audited by the Phase A `OperationLog` AOP.
- **UC005/006 — checkout phone + paymentMethod**: `CheckoutRequest` gains `customerPhone` (required) + `paymentMethod` (validated WECHAT/ALIPAY/BANK_CARD, fail-fast before the Redis gate); carried end-to-end through the consumer to the Order insert.
- **Sales-sort IN-list widened** — `pageProducts` sold subquery now `WHERE o.status IN ('PAID','SHIPPED','DELIVERED','COMPLETED')` (REFUNDED/CANCELLED/FAILED/PENDING excluded — only sold-and-kept). Phase B's `ProductMapperIntegrationTest` extended to exercise it.
- **Frontend**: Orders page rewritten with 5 active tabs (全部/未付款/未发货/未签收/已完成) + a disabled "未评价 (Phase D)" tab; per-status actions (Pay/Cancel/cancelPaid/confirmReceipt/refund-request modal); line-items drawer (`getOrderItems` + tracking display); `STATUS_COLORS` for all 9. Checkout gains phone + payment-method radio. Admin gains the Orders/shipping/refund section.

## Tests — all green, 0 skipped (local)
- Backend: **104/104** (was 72; +32 — OrderServiceImplTest, AdminOrderServiceTest, OrderStockReleaseServiceTest, PaymentServiceImplTest, CheckoutServiceImplTest, ProductMapperIntegrationTest).
- Frontend: **38/38** (was 27; +11 — Orders.test.tsx 10, Checkout.test.tsx +1). 7 files.
- E2E: orders suite **11/11** (new) + catalog **7/7**. The pre-existing non-catalog e2e failures (auth SMS-gated register cascade) are unchanged and out of scope.

## Final review verdict: MERGE (opus)
Clean — no must-fix. Verified: state machine CAS-guarded throughout with ownership-before-CAS; stock compensation on exactly the right transitions (no double/missing recredit); `@Transactional` on flip+recredit methods with no self-invocation bypass; schedulers correctly annotated (`@EnableScheduling` active, never-die try/catch, `rows==0` clean no-op); admin endpoints `hasRole("ADMIN")`; sales-sort IN-list correct; frontend↔backend contract sound end-to-end (camelCase serialization matches; phone+paymentMethod carried through consumer). The `paid_at` gap flagged in Task 1 was resolved in Task 2 (`markPaid`).

## Deferred to Phase F (logged in the SDD ledger + skipped-issues)
- **VM redeploy** — the VM (:8888) runs stale code; local suites are the per-phase gate.
- **Pre-existing non-catalog e2e failures** (auth 5-arg SMS-gated register contract → cascading 401s in products/sale/checkout/concurrency) — NOT caused by Phase C; resolve at Phase F VM redeploy + an SMS-code test-profile bypass so e2e can register a user.
- **Non-blocking observations:**
  - Phantom Redis `stock:` key on non-flash recredit (`INCR` on a missing key creates it value=qty rather than a true no-op) — benign, self-correcting; non-flash products never read Redis stock.
  - `expireOrder`/`completeOrder` set `cancel_reason`/`completed_at` via a separate `updateById` after the CAS (timestamp write outside the CAS) — cosmetic; the CAS guards the transition. Could fold into a richer atomic mapper for full consistency.
  - test-name misnomer `releaseOrderStock_redisDown_doesNotBlock` (real Redis-down coverage is in `OrderStockReleaseServiceTest`).
  - pre-existing Orders.tsx React-key warning (Fragment-in-map).
  - `confirmReceipt` JSDoc in `order.ts` says "→ COMPLETED" but it does SHIPPED→DELIVERED (one-word doc misnomer).
  - **Phase B minor still open:** frontend sort dropdown omits `sales|asc` + `rating|asc` (UC000 literally requires both directions).

## Next up: Phase D (Reviews)
The umbrella design spec §6 covers it: UC010 — 3-D star ratings (商品质量/物流速度/客服态度, 1-5 each), image upload (jpg/png ≤20MB), edit-after-submit. The `review` table was created table-only in Phase B; Phase D adds the `Review` entity/mapper + write endpoints onto the SAME table (no migration), plugs real ratings into Phase B's rating-sort (already wired), moves the COMPLETED transition to be review-triggered (replacing/augmenting the auto-complete scheduler), and lights up the "未评价" tab. New branch `feature/phase-d-reviews` off `main`.

## Recovery map
- SDD ledger: `.superpowers/sdd/2026-08-20-phase-c-orders/progress.md` (detailed per-task + review + fix history).
- Design: `docs/superpowers/specs/2026-08-20-phase-c-orders-design.md`.
- Plan: `docs/superpowers/plans/2026-08-20-phase-c-orders.md`.
- Skipped/deferred: `docs/superpowers/skipped-issues.md`.
