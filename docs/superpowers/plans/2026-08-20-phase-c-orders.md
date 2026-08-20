# Phase C (Order Lifecycle & Refunds) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement UC004/005/006/007/008/009/014 — extend the order state machine (SHIPPED/DELIVERED/COMPLETED/REFUND_PENDING/REFUNDED), 30-min unpaid expiry, cancel+refund flows (UC008 auto-refund, UC009 admin-approval), user confirm-receipt, admin order/shipping/refund management, checkout phone+paymentMethod, and the COMPLETED auto-complete scheduler.

**Architecture:** Reuse the existing `updateStatusIf` CAS primitive for transitions; add richer atomic updates where a transition must set extra columns (tracking_number, refund_status). A new `releaseOrderStock` helper compensates stock (recredit Redis + DB + DEL `seckill:`) on cancel/refund — fixing the gap the exploration found. Two new `@Scheduled` tasks (30-min expiry, daily auto-complete). A new `AdminOrderController` + `AdminOrderService`. Frontend Orders page gains sub-classification tabs + per-status actions + a line-items drawer; Checkout gains phone+paymentMethod; Admin gains an order/shipping/refund section.

**Tech Stack:** Spring Boot 4.1 / Java 21 / MyBatis-Plus 3.5.17 / MariaDB / Redis / JWT. React 18 + Vite + TS + Tailwind. JUnit + Mockito (unit) + `@SpringBootTest` (integration). Vitest + Testing Library (frontend). Custom Node e2e runner.

## Global Constraints

- **Branch:** `feature/phase-c-orders` off `main` (HEAD `62cc098`, Phase B merged). Commit per task; merge to `main` at the end.
- **Backend dir:** `C:/Users/tcmao/Desktop/newRepo/frame-backend`; **Frontend dir:** `C:/Users/tcmao/Desktop/newRepo/frame-frontend`; **e2e dir:** `C:/Users/tcmao/Desktop/newRepo/e2e`.
- **All status transitions use the CAS primitive** (`update orders set status=#{to} where id=#{id} and status=#{from}`) — no read-then-write. `rows==0` → `BusinessException(CONFLICT)`. Ownership checked in the service BEFORE the CAS.
- **`@Transactional`** on any service method that does a status flip + stock recredit in one logical operation (cancel, cancelPaid, approveRefund) — the DB status flip + DB stock recredit must be atomic. Redis recredit is best-effort (warn-log on failure, like the existing `getById` cache pattern — Redis being down must NOT block the order transition).
- **`seckill:{productId}:{userId}` key** — DEL on cancel (UC007/UC008) + on refund-approve (UC009). SET by the consumer on success (1h TTL). Non-flash-sale products have no `seckill:` key (DEL is a no-op).
- **Mock refund** (默认退款成功) — no real payment gateway. `refund_status` tracks the lifecycle on the `orders` row.
- **Scheduler pattern:** method-level `@Scheduled` on a `@Service`/`@Component`, try/catch swallowing errors so the scheduler never dies, shared 4-thread pool. No `:00`/`:30` cron for the daily one (use `7 4 * * *` — 4:07am, off the herd).
- **`orders.status` is `VARCHAR(16)`** — all new enum names fit (longest `REFUND_PENDING`=14). No column widening.
- **Integration tests** mirror `CheckoutFlowIntegrationTest`: `@SpringBootTest` + `@TestPropertySource` (no test profile), real MariaDB + Redis. The schema (incl. new `orders` columns) must be applied to the live dev DB inline (same as Phase B's `review` table — `application.yml` has no `spring.sql.init.mode`).
- **Backend test commands:** `cd C:/Users/tcmao/Desktop/newRepo/frame-backend && mvn -q -Dtest=ClassName test` (single); `mvn -q test` (full).
- **Frontend test command:** `cd C:/Users/tcmao/Desktop/newRepo/frame-frontend && npm test` (vitest run); `npx tsc --noEmit` (typecheck).
- **Skip-and-return:** hard bug → log to `docs/superpowers/skipped-issues.md`, `@Disabled`/`test.skip` with reason, proceed.
- **Auto mode:** user granted autonomous work — decide, don't ask. Log+skip genuinely critical items.

---

## File Structure

### Backend — create
- `frame-backend/src/main/java/com/iflytek/frame/common/RefundStatus.java` — enum NONE/PENDING/APPROVED/DENIED.
- `frame-backend/src/main/java/com/iflytek/frame/common/PaymentMethod.java` — enum WECHAT/ALIPAY/BANK_CARD.
- `frame-backend/src/main/java/com/iflytek/frame/service/AdminOrderService.java` — admin order/refund transitions.
- `frame-backend/src/main/java/com/iflytek/frame/service/impl/AdminOrderServiceImpl.java` — impl.
- `frame-backend/src/main/java/com/iflytek/frame/controller/AdminOrderController.java` — admin endpoints.
- `frame-backend/src/main/java/com/iflytek/frame/service/OrderLifecycleScheduler.java` — 30-min expiry + auto-complete schedulers.
- `frame-backend/src/test/java/com/iflytek/frame/service/OrderServiceImplTest.java` — unit (cancel/refund transitions + stock compensation).
- `frame-backend/src/test/java/com/iflytek/frame/service/AdminOrderServiceTest.java` — unit (admin transitions).
- `frame-backend/src/test/java/com/iflytek/frame/OrderLifecycleIntegrationTest.java` — integration (full lifecycle + schedulers).

### Backend — modify
- `frame-backend/src/main/resources/schema.sql` — add `orders` columns (customer_phone, payment_method, tracking_number, shipped_at, delivered_at, completed_at, paid_at, refund_status, refund_reason, cancel_reason).
- `frame-backend/src/main/java/com/iflytek/frame/common/OrderStatus.java` — add SHIPPED/DELIVERED/COMPLETED/REFUND_PENDING/REFUNDED.
- `frame-backend/src/main/java/com/iflytek/frame/entity/Order.java` — add the new fields.
- `frame-backend/src/main/java/com/iflytek/frame/dto/CheckoutRequest.java` — add customerPhone + paymentMethod.
- `frame-backend/src/main/java/com/iflytek/frame/service/OrderService.java` — add cancelPaid/requestRefund/confirmReceipt; myOrders status filter.
- `frame-backend/src/main/java/com/iflytek/frame/service/impl/OrderServiceImpl.java` — releaseOrderStock helper; cancel recredit; new methods; @Transactional.
- `frame-backend/src/main/java/com/iflytek/frame/mapper/OrderMapper.java` + `OrderMapper.xml` — selectPendingBefore, selectDeliveredBefore, updateStatusAndTracking, updateStatusAndRefund, myOrders-by-status.
- `frame-backend/src/main/java/com/iflytek/frame/service/impl/CheckoutServiceImpl.java` + `StreamConsumerService.java` — pass phone+paymentMethod into the order payload/insert.
- `frame-backend/src/main/java/com/iflytek/frame/controller/OrderController.java` — cancel-paid, refund-request, confirm-receipt, me?status=, items endpoints.
- `frame-backend/src/main/java/com/iflytek/frame/security/SecurityConfig.java` — admin order/refund matchers.
- `frame-backend/src/main/resources/mapper/ProductMapper.xml` — widen sales-sort IN-list (Task 2).

### Frontend — create
- `frame-frontend/src/api/order.ts` (extend, not create) — new user+admin functions.
- `frame-frontend/tests/Orders.test.tsx` — Orders page tabs+actions+drawer.

### Frontend — modify
- `frame-frontend/src/api/types.ts` — OrderStatus union + Order fields + CheckoutRequest + RefundStatus/PaymentMethod.
- `frame-frontend/src/api/order.ts` — getMyOrders status param + new functions.
- `frame-frontend/src/pages/Orders.tsx` — tabs + actions + drawer.
- `frame-frontend/src/pages/Checkout.tsx` — phone + paymentMethod fields.
- `frame-frontend/src/pages/Admin.tsx` — order/shipping/refund section.
- `frame-frontend/tests/Checkout.test.tsx` — phone+paymentMethod fields.

---

## Task 0: Branch + baseline

**Files:** (none — git only)

- [ ] **Step 1: Create the branch**
Run: `cd C:/Users/tcmao/Desktop/newRepo && git checkout -b feature/phase-c-orders`
Expected: on `feature/phase-c-orders`.

- [ ] **Step 2: Re-run the baseline gate (local)**
Run: `cd frame-backend && mvn -q test` (expect 72/72); `cd ../frame-frontend && npm test` (expect 27/27); `E2E_BASE_URL=http://localhost:8081 node e2e/run-tests.mjs` (catalog 7/7 — start the backend first if needed).
Expected: all green (the pre-existing non-catalog e2e failures are the known Phase A auth-contract issues — not regressions). If the backend won't start, log to skipped-issues + proceed (local suites are the gate).

- [ ] **Step 3: Commit nothing** (baseline only; no code change).

---

## Task 1: Backend enums + schema + Order entity + CheckoutRequest + mappers + service methods + controllers + scheduler + security (the backend core)

This is the large backend task. It's decomposed into TDD sub-steps. The implementer should commit once at the end (or per logical sub-step if preferred) after the full suite is green.

**Files:** all the backend create/modify files above (except ProductMapper.xml, which is Task 2).

**Interfaces:**
- Produces: `OrderStatus` (9 values), `RefundStatus`, `PaymentMethod`; `Order` with new fields; `OrderService.cancelPaid/requestRefund/confirmReceipt` + `myOrders(userId, query, status)`; `AdminOrderService` (adminListOrders/ship/deliver/refundRequests/approveRefund/denyRefund); `OrderLifecycleScheduler`; `OrderController` new endpoints; `AdminOrderController`; `SecurityConfig` admin matchers; `CheckoutRequest` phone+paymentMethod.

- [ ] **Step 1: Write the enums + extend `OrderStatus`**

`frame-backend/src/main/java/com/iflytek/frame/common/OrderStatus.java`:
```java
package com.iflytek.frame.common;

public enum OrderStatus {
    PENDING, PAID, CANCELLED, FAILED,
    SHIPPED, DELIVERED, COMPLETED,
    REFUND_PENDING, REFUNDED
}
```
`frame-backend/src/main/java/com/iflytek/frame/common/RefundStatus.java`:
```java
package com.iflytek.frame.common;

public enum RefundStatus {
    NONE, PENDING, APPROVED, DENIED
}
```
`frame-backend/src/main/java/com/iflytek/frame/common/PaymentMethod.java`:
```java
package com.iflytek.frame.common;

public enum PaymentMethod {
    WECHAT, ALIPAY, BANK_CARD
}
```

- [ ] **Step 2: Extend the `orders` table in `schema.sql`**

In the `CREATE TABLE orders` block, add these columns (keep existing ones). Insert before `create_time`:
```sql
    `customer_phone`   VARCHAR(20)  DEFAULT NULL,
    `payment_method`   VARCHAR(16)  DEFAULT NULL,
    `tracking_number`  VARCHAR(64)  DEFAULT NULL,
    `shipped_at`       DATETIME     DEFAULT NULL,
    `delivered_at`     DATETIME     DEFAULT NULL,
    `completed_at`     DATETIME     DEFAULT NULL,
    `paid_at`          DATETIME     DEFAULT NULL,
    `refund_status`    VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    `refund_reason`    VARCHAR(256) DEFAULT NULL,
    `cancel_reason`    VARCHAR(256) DEFAULT NULL,
```
No new index required (`idx_orders_status` already covers the status scans the schedulers use).

- [ ] **Step 3: Extend the `Order` entity**

`frame-backend/src/main/java/com/iflytek/frame/entity/Order.java` — add fields (keep existing + Lombok `@Data`):
```java
    private String customerPhone;
    private PaymentMethod paymentMethod;
    private String trackingNumber;
    private java.time.LocalDateTime shippedAt;
    private java.time.LocalDateTime deliveredAt;
    private java.time.LocalDateTime completedAt;
    private java.time.LocalDateTime paidAt;
    private RefundStatus refundStatus;
    private String refundReason;
    private String cancelReason;
```
Add imports for `PaymentMethod` + `RefundStatus`.

- [ ] **Step 4: Extend `CheckoutRequest`**

`frame-backend/src/main/java/com/iflytek/frame/dto/CheckoutRequest.java` — add (with `@NotBlank`):
```java
    @NotBlank(message = "customerPhone required")
    private String customerPhone;
    @NotBlank(message = "paymentMethod required")
    private String paymentMethod;   // WECHAT/ALIPAY/BANK_CARD — validated in the service
```

- [ ] **Step 5: Extend `OrderMapper` (Java) + `OrderMapper.xml`**

`OrderMapper.java` — add:
```java
    java.util.List<Order> selectPendingBefore(@Param("cutoff") java.time.LocalDateTime cutoff);
    java.util.List<Order> selectDeliveredBefore(@Param("cutoff") java.time.LocalDateTime cutoff);
    int updateStatusAndTracking(@Param("id") Long id, @Param("from") OrderStatus from, @Param("to") OrderStatus to,
                                @Param("tracking") String trackingNumber, @Param("shippedAt") java.time.LocalDateTime shippedAt);
    int updateStatusAndRefund(@Param("id") Long id, @Param("from") OrderStatus from, @Param("to") OrderStatus to,
                              @Param("refundStatus") RefundStatus refundStatus, @Param("reason") String reason);
    int updateMyOrdersStatus(@Param("userId") Long userId, ... // NOT a mapper method — see myOrders below
```
(The `myOrders` status filter uses a `LambdaQueryWrapper` `.eq(status)` in the service, not a new mapper method — simpler.)
Actual `OrderMapper.java` additions:
```java
    java.util.List<Order> selectPendingBefore(@Param("cutoff") java.time.LocalDateTime cutoff);
    java.util.List<Order> selectDeliveredBefore(@Param("cutoff") java.time.LocalDateTime cutoff);
    int updateStatusAndTracking(@Param("id") Long id, @Param("from") OrderStatus from, @Param("to") OrderStatus to,
                                @Param("tracking") String trackingNumber, @Param("shippedAt") java.time.LocalDateTime shippedAt);
    int updateStatusAndRefund(@Param("id") Long id, @Param("from") OrderStatus from, @Param("to") OrderStatus to,
                              @Param("refundStatus") RefundStatus refundStatus, @Param("reason") String reason);
```
Add imports: `RefundStatus`, `java.time.LocalDateTime`, `java.util.List`, `org.apache.ibatis.annotations.Param` (already there).

`OrderMapper.xml` — add (keep existing `selectByIdempotencyKey` + `updateStatusIf`):
```xml
    <select id="selectPendingBefore" resultType="com.iflytek.frame.entity.Order">
        select * from orders where status = 'PENDING' and create_time &lt; #{cutoff}
    </select>
    <select id="selectDeliveredBefore" resultType="com.iflytek.frame.entity.Order">
        select * from orders where status = 'DELIVERED' and delivered_at &lt; #{cutoff}
    </select>
    <update id="updateStatusAndTracking">
        update orders set status = #{to}, tracking_number = #{tracking}, shipped_at = #{shippedAt}, update_time = now()
        where id = #{id} and status = #{from}
    </update>
    <update id="updateStatusAndRefund">
        update orders set status = #{to}, refund_status = #{refundStatus},
               <if test="reason != null">refund_reason = #{reason},</if>
               update_time = now()
        where id = #{id} and status = #{from}
    </update>
```
Note: MyBatis-Plus `map-underscore-to-camel-case: true` maps the columns. The `<if test="reason != null">` avoids overwriting an existing reason with null on the deny path (where reason may be null).

- [ ] **Step 6: Write the failing unit tests for `OrderServiceImpl` (cancel/refund/confirm transitions + stock compensation)**

`frame-backend/src/test/java/com/iflytek/frame/service/OrderServiceImplTest.java` — Mockito unit test (mirror the existing `ProductServiceImplTest` style — `@ExtendWith(MockitoExtension.class)`, `@Mock OrderMapper`, `@Mock StringRedisTemplate`, `@Mock StockGateService`, `@Mock ProductMapper`, `@InjectMocks OrderServiceImpl`). Key tests (write real assertions, not tautologies):
- `cancel_pending_recreditsStockAndDelsSeckill` — seed a PENDING Order + its OrderItems; `updateStatusIf` returns 1; assert `stockGateService.recredit(pid, qty)` called per item, `productMapper.recreditStock(pid, qty)` called per item, `redis.delete("seckill:"+pid+":"+userId)` called per item.
- `cancel_nonPending_throwsConflict` — `updateStatusIf` returns 0 → `BusinessException(CONFLICT)`.
- `cancelPaid_paidToCancelled_setsRefundApproved_recreditsStock` — PAID Order; `updateStatusAndRefund(id, PAID, CANCELLED, APPROVED, reason)` returns 1; assert refund APPROVED + stock recredited + seckill DEL.
- `requestRefund_shippedToRefundPending` — SHIPPED Order; `updateStatusAndRefund(id, SHIPPED, REFUND_PENDING, PENDING, reason)` returns 1.
- `confirmReceipt_shippedToDelivered` — `updateStatusIf(id, SHIPPED, DELIVERED)` returns 1.
- `releaseOrderStock_redisDown_doesNotBlock` — Redis recredit throws `RedisConnectionFailureException`; assert the DB recredit + status flip still complete (warn-log, not throw).

Use `argThat`/`verify` for the stock calls. Stub `resolveOrderId`'s path by making `orderMapper.selectById(id)` return the seeded Order (so the fast-path resolves the id as a real PK). Stub `orderMapper.selectList(any())` (or whichever fetches OrderItems — see Step 7 for how items are loaded) to return the items.

- [ ] **Step 7: Implement `releaseOrderStock` + extend `cancel` + add new methods in `OrderServiceImpl`**

`OrderServiceImpl.java`:
- Add a `@Mock`/injected `OrderItemMapper orderItemMapper` + `StockGateService stockGateService` + `ProductMapper productMapper` to the constructor (the impl currently injects only `OrderMapper` + `StringRedisTemplate` — add the three new deps via the Lombok `@RequiredArgsConstructor` final fields).
- `releaseOrderStock(Order o)` (private):
```java
    private void releaseOrderStock(Order o) {
        // Load the order's items (snapshot) to recredit.
        var items = orderItemMapper.selectList(
            new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, o.getId()));
        for (OrderItem it : items) {
            try {
                stockGateService.recredit(it.getProductId(), it.getQuantity());      // Redis (flash-sale key; no-op if absent)
            } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
                log.warn("Redis recredit failed for product {} — DB recredit continues: {}", it.getProductId(), e.getMessage());
            }
            productMapper.recreditStock(it.getProductId(), it.getQuantity());        // DB (always)
            try {
                redis.delete("seckill:" + it.getProductId() + ":" + o.getUserId());  // release one-per-user lock
            } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
                log.warn("Redis seckill-delete failed for product {} — non-fatal: {}", it.getProductId(), e.getMessage());
            }
        }
    }
```
  Add a `slf4j Logger` (or use Lombok `@Slf4j`).
- `cancel(id, userId)` — extend: after the existing CAS `updateStatusIf(PENDING, CANCELLED)` succeeds, call `releaseOrderStock(o)`. Make the method `@Transactional`.
- `cancelPaid(id, userId)` (new, `@Transactional`):
```java
    @Override
    @org.springframework.transaction.annotation.Transactional
    public void cancelPaid(Long id, Long userId) {
        Long realPk = resolveOrderId(id);
        Order o = orderMapper.selectById(realPk);
        if (o == null || !o.getUserId().equals(userId)) throw new BusinessException(ResultCode.NOT_FOUND, "order not found");
        int rows = orderMapper.updateStatusAndRefund(realPk, OrderStatus.PAID, OrderStatus.CANCELLED, RefundStatus.APPROVED, null);
        if (rows == 0) throw new BusinessException(ResultCode.CONFLICT, "order cannot be cancelled in current state");
        releaseOrderStock(o);
    }
```
- `requestRefund(id, userId, reason)` (new): ownership check; `updateStatusAndRefund(id, SHIPPED, REFUND_PENDING, PENDING, reason)`; `rows==0` → CONFLICT. (No stock recredit here — item is in transit; recredit on approve.)
- `confirmReceipt(id, userId)` (new): ownership check; `updateStatusIf(id, SHIPPED, DELIVERED)`; `rows==0` → CONFLICT. (Optionally set `delivered_at` via a richer update — if using `updateStatusIf`, set `delivered_at` in a second `updateById`, or add `updateStatusAndDelivered`. For simplicity, use `updateStatusIf` + a separate `updateById` setting `delivered_at` — acceptable since the CAS guards the transition. OR add `updateStatusAndDelivered` mirroring `updateStatusAndTracking`. Prefer the richer atomic update to avoid a non-set delivered_at on a race — add `updateStatusAndDelivered` to the mapper. Implementer: add it if clean; otherwise `updateStatusIf` + `updateById` is acceptable with a comment.)
- `myOrders(userId, query, status)` — add an optional `status` param: `.eq(status != null, Order::getStatus, status)` in the wrapper. Update the interface signature + controller.

Update `OrderService` interface with the new method signatures.

- [ ] **Step 8: Run the unit tests to verify they pass**
Run: `cd C:/Users/tcmao/Desktop/newRepo/frame-backend && mvn -q -Dtest=OrderServiceImplTest test`
Expected: PASS. Iterate until green (TDD).

- [ ] **Step 9: Implement `AdminOrderService` + impl + controller**

`AdminOrderService.java`:
```java
package com.iflytek.frame.service;
import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.util.PageQuery;
public interface AdminOrderService {
    PageResult<Order> listOrders(PageQuery q, OrderStatus status);
    void ship(Long id, String trackingNumber);
    void deliver(Long id);
    PageResult<Order> refundRequests(PageQuery q);
    void approveRefund(Long id);
    void denyRefund(Long id);
}
```
`AdminOrderServiceImpl.java` — `@Service`, injects `OrderMapper` + `StockGateService` + `ProductMapper` + `OrderItemMapper` + `StringRedisTemplate`. Each transition uses the CAS:
- `ship`: `updateStatusAndTracking(id, PAID, SHIPPED, trackingNumber, LocalDateTime.now())`; `rows==0` → CONFLICT.
- `deliver`: `updateStatusIf(id, SHIPPED, DELIVERED)` (+ set `delivered_at`); CONFLICT on 0.
- `listOrders`: `LambdaQueryWrapper` `.eq(status!=null, Order::getStatus, status).orderByDesc(createTime)` + `selectPage`.
- `refundRequests`: `LambdaQueryWrapper` `.eq(Order::getStatus, REFUND_PENDING)`.
- `approveRefund` (`@Transactional`): `updateStatusAndRefund(id, REFUND_PENDING, REFUNDED, APPROVED, null)`; on success `releaseOrderStock(o)` (recredit — the item is now confirmed returned). Reuse the same `releaseOrderStock` logic — either duplicate it here (it's small) or extract to a shared helper bean. DECISION: extract `releaseOrderStock(Order)` to a small package-private static helper or a shared `OrderStockService` to avoid duplication. Simplest: a new `@Component OrderStockReleaseService` with `release(Order)` that both `OrderServiceImpl` and `AdminOrderServiceImpl` inject. Implementer: create `OrderStockReleaseService` + inject in both.
- `denyRefund`: `updateStatusAndRefund(id, REFUND_PENDING, SHIPPED, DENIED, null)`; CONFLICT on 0. No stock change.

`AdminOrderController.java` — `@RestController @RequestMapping("/admin")`, `@PreAuthorize("hasRole('ADMIN')")` (or rely on SecurityConfig matchers like the existing `AdminSaleController` — use SecurityConfig matchers for consistency):
```java
    @GetMapping("/orders")
    public Result<PageResult<Order>> listOrders(@Valid @ParameterObject PageQuery q,
            @RequestParam(required=false) OrderStatus status) { ... }
    @PostMapping("/orders/{id}/ship")
    public Result<Void> ship(@PathVariable Long id, @RequestBody ShipRequest body) { adminOrderService.ship(id, body.trackingNumber()); ... }
    @PostMapping("/orders/{id}/deliver")
    public Result<Void> deliver(@PathVariable Long id) { ... }
    @GetMapping("/refunds")
    public Result<PageResult<Order>> refunds(@Valid @ParameterObject PageQuery q) { ... }
    @PostMapping("/refunds/{orderId}/approve")
    public Result<Void> approveRefund(@PathVariable Long orderId) { ... }
    @PostMapping("/refunds/{orderId}/deny")
    public Result<Void> denyRefund(@PathVariable Long orderId) { ... }
```
`ShipRequest` — a small record `record ShipRequest(String trackingNumber)` in `dto`.

- [ ] **Step 10: Write + run `AdminOrderServiceTest` (unit)** — ship/deliver/approve/deny CAS transitions, CONFLICT on illegal from-state, approve recredits stock.

- [ ] **Step 11: Implement `OrderLifecycleScheduler`**

`OrderLifecycleScheduler.java`:
```java
package com.iflytek.frame.service;
import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class OrderLifecycleScheduler {
    private static final Logger log = LoggerFactory.getLogger(OrderLifecycleScheduler.class);
    private final OrderMapper orderMapper;
    private final OrderService orderService;   // for releaseOrderStock on expiry
    @Value("${frame.order.expire-minutes:30}") private int expireMinutes;
    @Value("${frame.order.complete-after-days:7}") private int completeAfterDays;

    /** UC004: cancel PENDING orders older than the expiry window. */
    @Scheduled(fixedDelay = 60_000)
    public void expirePendingOrders() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(expireMinutes);
            List<Order> stale = orderMapper.selectPendingBefore(cutoff);
            for (Order o : stale) {
                try { orderService.expireOrder(o.getId()); }   // CAS PENDING→CANCELLED + releaseOrderStock
                catch (Exception e) { log.warn("expire order {} failed: {}", o.getId(), e.getMessage()); }
            }
        } catch (Exception e) { log.warn("expirePendingOrders tick failed: {}", e.getMessage()); }
    }

    /** Auto-complete DELIVERED orders older than completeAfterDays. */
    @Scheduled(cron = "0 7 4 * * *")   // daily 4:07am, off the :00 herd
    public void autoCompleteOrders() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(completeAfterDays);
            List<Order> stale = orderMapper.selectDeliveredBefore(cutoff);
            for (Order o : stale) {
                try { orderService.completeOrder(o.getId()); }  // CAS DELIVERED→COMPLETED + completed_at
                catch (Exception e) { log.warn("complete order {} failed: {}", o.getId(), e.getMessage()); }
            }
        } catch (Exception e) { log.warn("autoCompleteOrders tick failed: {}", e.getMessage()); }
    }
}
```
Add `expireOrder(Long id)` + `completeOrder(Long id)` to `OrderService`:
- `expireOrder`: `@Transactional`; load Order; CAS `updateStatusIf(PENDING, CANCELLED)`; on success `releaseOrderStock(o)` + set `cancel_reason='expired'`.
- `completeOrder`: CAS `updateStatusIf(DELIVERED, COMPLETED)` (+ set `completed_at`). CONFLICT on 0 is fine (scheduler logs + moves on).

- [ ] **Step 12: Update `OrderController`** — add:
```java
    @GetMapping("/me")  // extend: accept optional ?status=
    public Result<PageResult<Order>> myOrders(@Valid @ParameterObject PageQuery q,
            @RequestParam(required=false) OrderStatus status) {
        return Result.success(orderService.myOrders(SecurityUtils.currentUserId(), q, status));
    }
    @PostMapping("/{id}/cancel-paid")
    public Result<Void> cancelPaid(@PathVariable Long id) { orderService.cancelPaid(id, SecurityUtils.currentUserId()); return Result.success(); }
    @PostMapping("/{id}/refund-request")
    public Result<Void> refundRequest(@PathVariable Long id, @RequestBody RefundRequestRequest body) {
        orderService.requestRefund(id, SecurityUtils.currentUserId(), body.reason()); return Result.success();
    }
    @PostMapping("/{id}/confirm-receipt")
    public Result<Void> confirmReceipt(@PathVariable Long id) { orderService.confirmReceipt(id, SecurityUtils.currentUserId()); return Result.success(); }
    @GetMapping("/{id}/items")
    public Result<java.util.List<com.iflytek.frame.entity.OrderItem>> items(@PathVariable Long id) {
        return Result.success(orderService.listItems(id, SecurityUtils.currentUserId()));
    }
```
`RefundRequestRequest` — `record RefundRequestRequest(String reason)`. `listItems(id, userId)` — ownership check then `orderItemMapper.selectList(eq(orderId))`.

- [ ] **Step 13: Wire phone+paymentMethod through checkout**

`CheckoutServiceImpl.checkout` — add `customerPhone` + `paymentMethod` (validate paymentMethod is one of WECHAT/ALIPAY/BANK_CARD) into the JSON payload built for the consumer. `StreamConsumerService.processPayload` — set `order.setCustomerPhone(...)` + `order.setPaymentMethod(PaymentMethod.valueOf(...))` before `insert(order)`.

- [ ] **Step 14: Update `SecurityConfig`** — add to the admin `hasRole("ADMIN")` block:
```java
.requestMatchers(org.springframework.http.HttpMethod.GET, "/admin/orders", "/admin/refunds").hasRole("ADMIN")
.requestMatchers(org.springframework.http.HttpMethod.POST,
        "/admin/orders/{id}/ship", "/admin/orders/{id}/deliver",
        "/admin/refunds/{orderId}/approve", "/admin/refunds/{orderId}/deny").hasRole("ADMIN")
```
The user `/orders/me` + `/orders/{id}/*` endpoints stay under `anyRequest().authenticated()`.

- [ ] **Step 15: Run the full backend suite + fix regressions**
Run: `cd C:/Users/tcmao/Desktop/newRepo/frame-backend && mvn -q test`
Expected: all green (existing 72 + new tests). Existing tests that assert the old 4-value `OrderStatus` or the old `myOrders(userId, query)` 2-arg signature must be updated to the new signature. If a hard breakage → skip-and-return (log + `@Disabled`).

- [ ] **Step 16: Commit**
```bash
git add -A frame-backend/
git commit -m "feat(phase-c): order state machine + cancel/refund + schedulers + admin mgmt + checkout phone/payment"
```

---

## Task 2: Widen the sales-sort IN-list (Phase B `pageProducts`)

**Files:**
- Modify: `frame-backend/src/main/resources/mapper/ProductMapper.xml`
- Modify: `frame-backend/src/test/java/com/iflytek/frame/mapper/ProductMapperIntegrationTest.java` (the sales test)

- [ ] **Step 1: Widen the `catalogFrom` sold subquery**

In `ProductMapper.xml`, the `<sql id="catalogFrom">` sold subquery:
```xml
        LEFT JOIN (
            SELECT oi.product_id, SUM(oi.quantity) AS sold
            FROM order_item oi
            JOIN orders o ON o.id = oi.order_id
            WHERE o.status IN ('PAID', 'SHIPPED', 'DELIVERED', 'COMPLETED')
            GROUP BY oi.product_id
        ) s ON s.product_id = p.id
```
(REFUNDED/CANCELLED/FAILED/PENDING excluded — only actually-sold-and-kept orders count.)

- [ ] **Step 2: Update the `ProductMapperIntegrationTest.sales_sort_desc_paidOnly` test**

The test seeds a PAID order (4 units) + a PENDING order (9 units). After widening, still only PAID counts (PENDING excluded) — the test's assertion (sold==4) still holds. ADD a SHIPPED order (e.g. 3 units of a product) and assert it now counts toward sold (so the IN-list widening is actually exercised). Rename/extend the test to `sales_sort_desc_paidAndShipped`.

- [ ] **Step 3: Run + verify**
Run: `cd C:/Users/tcmao/Desktop/newRepo/frame-backend && mvn -q -Dtest=ProductMapperIntegrationTest test`
Expected: PASS (incl. the new SHIPPED-counts assertion).

- [ ] **Step 4: Commit**
```bash
git add frame-backend/src/main/resources/mapper/ProductMapper.xml frame-backend/src/test/java/com/iflytek/frame/mapper/ProductMapperIntegrationTest.java
git commit -m "feat(phase-c): sales-sort counts PAID+SHIPPED+DELIVERED+COMPLETED (exclude refunded)"
```

---

## Task 3: Frontend types + API client

**Files:**
- Modify: `frame-frontend/src/api/types.ts`
- Modify: `frame-frontend/src/api/order.ts`

- [ ] **Step 1: Extend types**

`types.ts`:
- `OrderStatus` → `'PENDING'|'PAID'|'CANCELLED'|'FAILED'|'SHIPPED'|'DELIVERED'|'COMPLETED'|'REFUND_PENDING'|'REFUNDED'`.
- Extend `Order` with: `customerPhone?`, `paymentMethod?`, `trackingNumber?`, `shippedAt?`, `deliveredAt?`, `completedAt?`, `paidAt?`, `refundStatus?`, `refundReason?`, `items?: OrderItem[]`.
- Extend `CheckoutRequest` with `customerPhone: string`, `paymentMethod: 'WECHAT'|'ALIPAY'|'BANK_CARD'`.
- Add: `export type RefundStatus = 'NONE'|'PENDING'|'APPROVED'|'DENIED'`.

- [ ] **Step 2: Extend `order.ts`**
- `getMyOrders(current=1, size=20, status?: OrderStatus)` — add the status param.
- Add user: `cancelPaid(id)`, `requestRefund(id, reason)`, `confirmReceipt(id)`, `getOrderItems(id)`.
- Add admin: `adminListOrders(status?, current?, size?)`, `adminShip(id, trackingNumber)`, `adminDeliver(id)`, `adminRefundRequests()`, `adminApproveRefund(orderId)`, `adminDenyRefund(orderId)`.

- [ ] **Step 3: Typecheck**
Run: `cd C:/Users/tcmao/Desktop/newRepo/frame-frontend && npx tsc --noEmit`
Expected: clean (or only expected errors in Orders.tsx/Checkout.tsx/Admin.tsx that the later tasks fix).

- [ ] **Step 4: Commit**
```bash
git add frame-frontend/src/api/types.ts frame-frontend/src/api/order.ts
git commit -m "feat(phase-c): frontend types + order API (lifecycle, admin, status filter)"
```

---

## Task 4: Frontend Orders page — tabs + actions + line-items drawer

**Files:**
- Modify: `frame-frontend/src/pages/Orders.tsx`
- Create: `frame-frontend/tests/Orders.test.tsx`

- [ ] **Step 1: Write the failing Orders tests**

`tests/Orders.test.tsx` — mock `@/api/order`; render `<MemoryRouter><CartProvider><Orders/></CartProvider></MemoryRouter>`. Tests:
- tabs render (全部/未付款/未发货/未签收/已完成 + disabled 未评价 (Phase D)); clicking 未付款 calls `getMyOrders` with `status:'PENDING'`.
- PENDING order row shows Pay + Cancel buttons; Cancel calls `cancelOrder`.
- PAID order row shows a Cancel (refund) button; calls `cancelPaid`.
- SHIPPED order row shows Confirm-receipt + Request-refund buttons; confirm calls `confirmReceipt`; refund opens a reason modal + calls `requestRefund`.
- REFUND_PENDING row shows "退款处理中".
- line-items drawer: clicking a row expands + calls `getOrderItems(id)` + shows the item names/qty.

- [ ] **Step 2: Run tests to verify RED**
Run: `cd C:/Users/tcmao/Desktop/newRepo/frame-frontend && npm test -- Orders`
Expected: FAIL (no tabs/actions yet).

- [ ] **Step 3: Rewrite `Orders.tsx`** — add the 5 active tabs (全部=all / 未付款=PENDING / 未发货=PAID / 未签收=SHIPPED / 已完成=COMPLETED) + a disabled 未评价 (Phase D) tab; `activeStatus` state; `getMyOrders(current, size, activeStatus)`; per-status action buttons; a refund-reason modal; an expandable line-items drawer via `getOrderItems`. Extend `STATUS_COLORS` for the 5 new statuses. Keep the existing Refresh + empty state. `loading="lazy"` on any images.

- [ ] **Step 4: Run tests to verify GREEN**
Run: `cd C:/Users/tcmao/Desktop/newRepo/frame-frontend && npm test -- Orders`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add frame-frontend/src/pages/Orders.tsx frame-frontend/tests/Orders.test.tsx
git commit -m "feat(phase-c): Orders page tabs + per-status actions + line-items drawer"
```

---

## Task 5: Frontend Checkout (phone + paymentMethod) + Admin order section

**Files:**
- Modify: `frame-frontend/src/pages/Checkout.tsx`
- Modify: `frame-frontend/tests/Checkout.test.tsx`
- Modify: `frame-frontend/src/pages/Admin.tsx`

- [ ] **Step 1: Extend `Checkout.tsx`** — add `customerPhone` (required) + a payment-method radio (微信/支付宝/银行卡) to the form; include both in the `CheckoutRequest`. Extend `STATUS_COLORS`/`StatusBadge` for the new statuses.

- [ ] **Step 2: Extend `Checkout.test.tsx`** — assert the phone + payment-method fields exist, are required, and are submitted in the checkout payload.

- [ ] **Step 3: Extend `Admin.tsx`** — add an "Orders" tab/section: an order table (filterable by status) with Ship (tracking-number input → `adminShip`), Deliver (`adminDeliver`), and a Refunds sub-section listing REFUND_PENDING orders with Approve/Deny buttons. Reuse `useIsAdmin()`.

- [ ] **Step 4: Run frontend suite + typecheck**
Run: `cd C:/Users/tcmao/Desktop/newRepo/frame-frontend && npm test && npx tsc --noEmit`
Expected: all green (27 + new Orders/Checkout tests).

- [ ] **Step 5: Commit**
```bash
git add frame-frontend/src/pages/Checkout.tsx frame-frontend/tests/Checkout.test.tsx frame-frontend/src/pages/Admin.tsx
git commit -m "feat(phase-c): checkout phone+paymentMethod + admin order/shipping/refund section"
```

---

## Task 6: E2E order-lifecycle flow + full regression + final review + merge

**Files:**
- Create: `e2e/tests/orders.test.mjs` (new suite) + wire into `run-tests.mjs`.

- [ ] **Step 1: Write the order-lifecycle e2e**

`e2e/tests/orders.test.mjs` — guest/user/admin flow against local Phase C backend (use `http`, `assert`, `ctx.adminToken`/`ctx.userToken` from the existing suites' login setup). Cases:
- checkout → pay (PENDING→PAID).
- admin ship (PAID→SHIPPED, tracking set).
- user confirm-receipt (SHIPPED→DELIVERED).
- cancel unpaid (UC007): checkout a 2nd order, don't pay, cancel → CANCELLED + (assert via admin list the stock was recredited, or just assert status).
- cancel paid pre-ship (UC008): checkout+pay a 3rd, cancel-paid → CANCELLED + refund APPROVED.
- post-ship refund (UC009): checkout+pay+ship a 4th, user refund-request → REFUND_PENDING, admin approve → REFUNDED.
- legal-transition guards: confirm-receipt on a PENDING order → 409; ship a non-PAID → 409.
Adapt the `http` helper import + assertion style to the existing suites. Guard sparse-state assertions.

- [ ] **Step 2: Wire + run**
Wire `ordersSuite` into `run-tests.mjs`. Start the backend (`mvn spring-boot:run`). Run `E2E_BASE_URL=http://localhost:8081 node e2e/run-tests.mjs`. Expected: orders suite green + catalog (7) still green. Skip-and-return on a hard bug.

- [ ] **Step 3: Full regression**
Run: `cd frame-backend && mvn -q test` (expect all green incl. new); `cd frame-frontend && npm test` (expect all green).

- [ ] **Step 4: Final whole-branch review**
Run `scripts/review-package PLAN_FILE MERGE_BASE HEAD`; dispatch the final reviewer (most capable model) with the package + ledger. Address Critical/Important; defer Minor to Phase F.

- [ ] **Step 5: Merge + status doc + memory**
Merge `feature/phase-c-orders`→`main` (--no-ff). Re-run regression on main. Write `docs/superpowers/PHASE-C-STATUS.md`. Update memory `frame-shop-uc-fulfillment` (resume = Phase D). Delete the SDD workspace.

---

## Self-Review

**1. Spec coverage:** UC004 (Task 1 scheduler), UC005/006 (paymentMethod recorded; pay exists), UC007 (Task 1 cancel+recredit), UC008 (Task 1 cancelPaid), UC009 (Task 1 requestRefund + admin approve/deny), UC014 (Task 1 AdminOrderController + Task 5 Admin section), COMPLETED+auto-complete (Task 1 scheduler), checkout phone+paymentMethod (Task 1 backend + Task 5 frontend), sales-sort IN-list widen (Task 2). Reviews (UC010) + 未评价 tab explicitly deferred to Phase D. ✓

**2. Placeholder scan:** The `confirmReceipt` delivered_at handling (Step 7) offers two implementations ("add updateStatusAndDelivered if clean; otherwise updateStatusIf + updateById with a comment") — the implementer picks one. That's a real choice, not a placeholder; both are valid. No TBD/TODO.

**3. Type consistency:** `OrderStatus` 9 values consistent across enum (Task 1 Step 1), entity, mapper transitions, frontend types (Task 3). `RefundStatus` NONE/PENDING/APPROVED/DENIED consistent. `releaseOrderStock` used by cancel/cancelPaid/expireOrder/approveRefund — extracted to `OrderStockReleaseService` (Step 9) to avoid duplication. `myOrders(userId, query, status)` 3-arg signature consistent across interface/impl/controller/frontend. The `Order` field names match the snake_case columns via the existing camel-case mapping. ✓

One note: the scheduler calls `orderService.expireOrder`/`completeOrder` — these must be added to the `OrderService` interface (Step 11 notes this). The implementer must ensure both are declared + implemented (Step 7/11) — flagged here for consistency.
