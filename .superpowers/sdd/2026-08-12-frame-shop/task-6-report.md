# Task 6 Report: Payment + Queued-Status Endpoint + Cancel

## What was implemented

### Already there (from Task 5 fix round 2, built on)
- `OrderServiceImpl.resolveOrderId(Long correlationOrPk)` — private method that resolves a correlation id to the DB PK via `order_id_map:{corrId}` Redis key. `getById`, `cancel`, and `flipStatus` all delegate to it.
- `OrderService.flipStatus(Long id, OrderStatus from, OrderStatus to)` — atomic conditional update (returns `to` on success, throws CONFLICT on mismatch). Already resolves via `resolveOrderId`.
- `StringRedisTemplate redis` already injected into `OrderServiceImpl`.

### Added in Task 6
1. **`PaymentService` interface** (`service/PaymentService.java`) — `Order pay(Long orderId, Long userId)`.
2. **`PaymentServiceImpl`** (`service/impl/PaymentServiceImpl.java`) — thin delegation: calls `orderService.flipStatus(orderId, PENDING, PAID)` (the atomic guard), then `orderService.getById(orderId, userId)` to return the flipped Order. ~20 lines total. Mock payment always succeeds per mentor's instruction.
3. **`POST /orders/{id}/pay` endpoint** on `OrderController` — injects `PaymentService`, delegates to `pay(id, SecurityUtils.currentUserId())`.
4. **Queued-status handling in `getById`** (`OrderServiceImpl`) — when `selectById` would miss (the order row doesn't exist yet) but `order_status:{id}` = "queued" in Redis, returns a synthetic `Order` with `id` + `status=PENDING` (placeholder) + `userId`, so the frontend can poll. The queued check runs FIRST (before `resolveOrderId`), since the queued case means neither the PK nor the map will resolve.
5. **Tests**: `PaymentServiceImplTest` (2 tests: flip + return-flipped-order) + new `getById_queuedInRedis_returnsSyntheticOrderForPolling` test in `OrderServiceImplTest`.

## TDD Evidence

### RED (Step 2)
```
mvn -q -Dtest=PaymentServiceImplTest test
→ COMPILATION ERROR: cannot find symbol: class PaymentServiceImpl
```
`PaymentServiceImpl` did not exist — test could not compile.

### GREEN (Step 4)
```
mvn -Dtest=PaymentServiceImplTest test
→ Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
→ BUILD SUCCESS
```

## Full `mvn clean test` Result (Step 7)
```
Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
Breakdown:
- FrameBackendApplicationTests: 1
- CheckoutServiceImplTest: 4
- OrderServiceImplTest: 5 (4 prior + 1 new queued test)
- PaymentServiceImplTest: 2 (new)
- ProductServiceImplTest: 3
- StockGateServiceImplTest: 4
- StreamConsumerServiceTest: 2
- UserServiceImplTest: 1

## Files Changed
- **Created:** `frame-backend/src/main/java/com/iflytek/frame/service/PaymentService.java`
- **Created:** `frame-backend/src/main/java/com/iflytek/frame/service/impl/PaymentServiceImpl.java`
- **Created:** `frame-backend/src/test/java/com/iflytek/frame/service/PaymentServiceImplTest.java`
- **Modified:** `frame-backend/src/main/java/com/iflytek/frame/controller/OrderController.java` (added `PaymentService` injection + `pay` endpoint)
- **Modified:** `frame-backend/src/main/java/com/iflytek/frame/service/impl/OrderServiceImpl.java` (added queued-status check in `getById`)
- **Modified:** `frame-backend/src/test/java/com/iflytek/frame/service/OrderServiceImplTest.java` (added `order_status` stubs to existing tests + new queued test)

## Self-Review Findings
- `PaymentServiceImpl` is ~20 lines — a thin delegation to `flipStatus` + `getById`, exactly as the brief specifies. Not overbuilt.
- The queued check in `getById` runs BEFORE `resolveOrderId` — this is correct because the queued case (consumer hasn't persisted) means neither `selectById(id)` nor `order_id_map:{id}` will resolve. Checking `order_status:{id}` first avoids a spurious NOT_FOUND from `resolveOrderId`.
- The synthetic queued Order uses `status=PENDING` as a placeholder per the brief Step 5 — the frontend polls until it gets the real row (with PENDING/FAILED/CANCELLED). The brief mentions this is the V1 approach (Order DTO carries status); a dedicated `/orders/{id}/status` endpoint is a cleaner alternative noted in the brief.
- Existing `OrderServiceImplTest` tests were updated to stub `valueOps.get("order_status:" + id)` because `getById` now calls it on every path (before `resolveOrderId`). This is a legitimate test update reflecting the new behavior — the `verify(redis, never()).opsForValue()` assertion on the fast path was replaced with `verify(valueOps, never()).get("order_id_map:" + realPk)` (the order_id_map is still not consulted on the fast path; only order_status is).

## Concerns
- The queued check adds one Redis `GET` to every `getById` call (even when the order exists in DB). This is a minor latency cost on the happy path. An alternative would be to catch the NOT_FOUND from `resolveOrderId` and then check queued, but that's messier (exception-as-control-flow). The current approach is cleaner and the Redis GET is O(1). Acceptable for V1.
- The synthetic queued Order returns `userId = the requesting user` without verification (the order row doesn't exist yet to check ownership). This is acceptable because the correlation id is opaque and the queued status carries no sensitive data — the frontend just polls. Once the consumer persists, the real ownership check applies.
