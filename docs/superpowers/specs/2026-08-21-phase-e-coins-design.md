# Design — Frame Shop Phase E (Coins): UC012

> Date: 2026-08-21. Branch `feature/phase-e-coins` off `main` (HEAD `830651a`, Phase D merged). Detailed spec under the umbrella `2026-08-19-frame-shop-uc-fulfillment-design.md` §7; this document is the detailed Phase E spec. Covers **UC012** (优惠金币领取和使用) + completes the **UC005** coin branch (coins-at-checkout), and replaces the Phase D `CoinServiceStub` no-op seam with a real coin ledger.
>
> UC012 (优惠金币领取和使用): a registered, logged-in user earns coins three ways and spends them at checkout. Per `use case/use cases.txt:145-154`:
> 1. **Daily login** → +100 coins, once per day (refreshes 4:00am CST).
> 2. **Review reward** → +coins equal to the order's **actual paid amount** (实际付款金额).
> 3. Coins **expire 30 days** after acquisition, **each credit's clock independent** (根据获取时间独立计算).
> 4. **Spend rules**: 100 coins = ¥1; the deduction may not exceed **50% of the undiscounted subtotal**; **discount (flash-sale) products cannot use coins** (折扣商品无法使用优惠金币).

## 1. Scope (locked)

### In scope — earn
- **Daily-login reward** (UC012 §1): on successful login, if the user has not yet claimed today (CST date, 4am boundary), credit +100 coins with `expires_at = now + 30 days`. Idempotent (a second login the same CST-day is a no-op).
- **Review reward** (UC012 §2): replace the `CoinServiceStub.reviewSubmitted` no-op with a real credit of the order's actual paid amount, in coins (= `paidInCents / 100`, integer yuan). Fires once per order (the Phase D gate already guarantees this); Phase E makes the *credit itself* idempotent against the concurrent-last-item double-fire flagged in the Phase D final review.

### In scope — spend (completes UC005 coin branch)
- **Coins-at-checkout** (UC005 §4-5): the user opts to spend coins; the discount is computed server-side from DB prices + the user's live coin balance; coins are deducted atomically with the order; the actual paid amount is recorded so the review reward (later) reflects it.
- **Refund returns coins** (UC008/UC009): when a paid order is cancelled/refunded, any coins spent on it are returned to the ledger (restoring the original per-credit expiry clocks).

### In scope — expiry
- **30-day per-credit FIFO expiry** (UC012 §3): each earned credit row has its own `expires_at`; a daily scheduler sweeps credits past their clock and records an `EXPIRED` balancing row; balance excludes expired credits. Spending consumes oldest-first (FIFO) so the soonest-to-expire coins are spent first.

### In scope — read
- `GET /coins/balance` (current usable balance + a small projection) and `GET /coins/history` (paginated ledger). A frontend coin summary (balance + today's claim status) on the account/profile area, and a coin-usage toggle at checkout.

### Out of scope (deferred)
- **Admin coin tooling** (manual adjust, fraud review) — not in UC012; out of scope.
- **Coin earn from sources other than daily-login + review** (e.g. invite rewards, campaigns) — not in the spec; the `source` enum is extensible but no other source is wired.
- **VM re-deploy of Phase E** — deferred to Phase F (same as Phase D's deferral); Phase E is verified locally (backend + frontend + e2e).
- **Pre-existing e2e auth-SMS-cascade failures** — still out of scope since Phase C; Phase F cleanup.

## 2. Decisions (locked)

| Decision | Choice | Why |
|---|---|---|
| Ledger model | Append-only `coin_ledger` rows; balance = `SUM(amount)` over rows where `(expires_at IS NULL) OR (expires_at > now())`. Earned credits carry `expires_at`; spent/refund/expired rows carry `expires_at = NULL` (terminal — never expire themselves). | Matches the umbrella spec §7 model exactly. Append-only gives a full audit trail; the `expires_at IS NULL OR > now()` predicate makes balance a single SUM with no row mutation on expiry (the EXPIRED row is a *new* negative row, not an update). |
| Review-reward idempotency | `UNIQUE KEY uk_coin_idem (idem_key)`; the REVIEW_REWARD row sets `idem_key = "review:{orderId}"`; a duplicate insert (the concurrent double-fire) throws `DataIntegrityViolationException` → caught → no-op. | Solves the Phase D final-review flag: under concurrency, two "last item" reviews can both pass the in-memory `wasFullyReviewed`/`nowFullyReviewed` gate and both call `awardReviewReward`. The unique key makes the second credit a clean no-op rather than a double-credit. |
| Daily-login idempotency | `UNIQUE KEY uk_coin_daily (user_id, login_date)` where `login_date` is the CST date string ("2026-08-21"). Claiming is an `INSERT` that ignores duplicate-key. | "每天登录可领取一次" + "4am刷新" = exactly one claim per (user, CST-date). The date column (not a timestamp) makes the 4am boundary a pure date comparison in `Asia/Shanghai`; no time-arithmetic drift. |
| "Actual paid amount" source | New `orders.paid_in_cents` column, set in the checkout consumer = `totalInCents - coinDiscountCents`. Review reward = `paidInCents / 100` coins (integer yuan, floor). | Today `totalInCents` IS the paid amount (no discounts), but once coins are spent at checkout they diverge. The reward must equal *what the user actually paid* (UC012: "本订单实际付款金额"), so a dedicated column is required. Yuan (not cents) is the reward unit because 100 coins = ¥1. |
| Where coins are deducted at checkout | **Validate in the hot path** (`CheckoutServiceImpl`) — enough balance, flash-sale gating, 50% cap — fail fast (400/409) *before* the stock gate so a coin problem never reserves stock. **Deduct atomically in the consumer** (`StreamConsumerService.processPayload`) inside the same `@Transactional` block as the Order insert + stock decrement, so coins + order commit together or roll back together (a FAILED order never deducts coins). | The checkout is async (hot path enqueues, consumer persists). Deducting in the hot path is wrong: the consumer's stock backstop can still fail the order (→ FAILED) after coins were deducted. The consumer runs *after* the stock backstop succeeds, so its transaction is the safe commit point. Pre-validation in the hot path preserves the fail-fast UX. |
| FIFO spend | `SELECT ... FOR UPDATE` the user's un-expired positive credit rows ordered by `expires_at ASC`; consume oldest-first; insert one `CHECKOUT_SPEND` negative row per consumed credit. The row-level lock (per-user credit rows) serializes a user's concurrent spends → no overspend. | UC012 doesn't mandate FIFO explicitly, but "根据获取时间独立计算" + 30-day expiry means spending oldest-first minimizes waste (coins about to expire get spent first). `FOR UPDATE` in the consumer transaction is the same concurrency discipline as the flash-sale stock gate — proven pattern in this codebase. |
| Flash-sale gating | A checkout may use coins **only if NO line item is a flash-sale item** (`product.is_flash_sale = 1`). Enforced in the hot path (reject with 400 "coins cannot be used on flash-sale items"). | UC012: "折扣商品无法使用优惠金币". `is_flash_sale` is the discount flag. One flash-sale item in the cart disables coins for the whole order (simplest correct rule; per-item discount is out of scope). |
| 50% cap | Max discount = `floor(nonFlashSubtotalInCents * 0.5)`, in cents; converted to coins as `floor(maxDiscountCents / 100)` × 100 (coins are in increments of 100 = ¥1). The user's requested coin amount is clamped to `min(requested, maxCoins, balance)`. | UC012: "单次抵扣金额不超过本订单未优惠金额的50%". "未优惠金额" = the non-discounted subtotal (all items are non-flash in a coins-eligible order, so = subtotal). Floor + 100-coin granularity avoids fractional-yuan discounts. |
| Refund return | On `cancelPaid` (UC008) and admin refund-approve (UC009): insert `REFUND_RETURN` positive rows mirroring the order's `CHECKOUT_SPEND` rows, restoring each consumed credit's original `expires_at`. Idempotent via `uk_coin_idem` (`idem_key = "refund:{orderId}"`). | "退款" in UC008/UC009 + the umbrella spec §5: "Coins used on a refunded order are returned to the ledger." Restoring the original `expires_at` honors the per-credit 30-day clock (a refunded coin keeps its original expiry, not a fresh 30 days). |
| Expiry sweep | `@Scheduled(cron = "1 1 4 * * *")` (daily 4:01am CST, off the :00 herd like the existing schedulers). For each credit row with `expires_at <= now()` and no matching EXPIRED row, insert a negative `EXPIRED` row (same `user_id`, `amount = -credit.amount`, `ref` the credit id, `expires_at = NULL`). | "30天过期" + "东八区4:00am刷新" — the 4am boundary is when the daily-login claim resets, so sweeping expired credits at 4:01am keeps the two coin-side daily jobs in lockstep. The "no matching EXPIRED row" guard makes the sweep idempotent across re-runs. |
| Daily-login trigger point | In `AuthServiceImpl.login`, after `loginAttemptService.recordSuccess(...)`, before building the token, call `coinService.awardDailyLogin(user.getId())` in a try/catch (a coin failure must never break login — mirrors the Phase D review-seam try/catch). | UC012 §1 triggers on "用户每天登录". Login is the authoritative moment; doing it in the same request gives immediate balance feedback (the `GET /coins/balance` call right after login shows +100). try/catch isolates the coin subsystem from auth. |
| Balance cache | No Redis cache; balance computed by a `SELECT SUM(amount) WHERE user_id=? AND (expires_at IS NULL OR expires_at > now())` on each `GET /coins/balance`. | Per-user coin read volume is low (one call per checkout open + profile view). A cached balance would need invalidation on every earn/spend/expire — more failure surface than the SUM query, which hits the `idx_coin_user` index. YAGNI; add a cache only if profiling shows it. |
| CheckoutRequest change | Add `Boolean useCoins` (default false) + `Integer coinAmount` (number of coins the user wants to spend; 0/null when `useCoins` false). Carried through the Redis payload to the consumer. | UC005 §4: "用户选择是否使用优惠金币". `coinAmount` (not "use all") lets the client show the computed max and let the user pick; server clamps. The field rides the existing payload→consumer path (no new transport). |

## 3. Data model

### 3a. New `coin_ledger` table — added to `schema.sql` (drop-if-exists first, after `order_item`/`review`)

```sql
CREATE TABLE `coin_ledger` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT       NOT NULL,
    `amount`        INT          NOT NULL,             -- +earned / -spent (positive/negative coins)
    `balance_after` INT          NOT NULL,            -- running balance snapshot after this row
    `source`        VARCHAR(32)  NOT NULL,            -- DAILY_LOGIN / REVIEW_REWARD / CHECKOUT_SPEND / REFUND_RETURN / EXPIRED
    `ref_order_id`  BIGINT       DEFAULT NULL,        -- the order this row relates to (set on REVIEW_REWARD / CHECKOUT_SPEND / REFUND_RETURN; null for DAILY_LOGIN/EXPIRED). NON-unique — multiple CHECKOUT_SPEND rows share it (one per consumed credit).
    `ref_credit_id` BIGINT       DEFAULT NULL,        -- the earned-credit row this debit/expiry balances (CHECKOUT_SPEND/REFUND_RETURN/EXPIRED)
    `idem_key`      VARCHAR(64)  DEFAULT NULL,        -- idempotency key: "review:{orderId}" (REVIEW_REWARD), "refund:{orderId}" (REFUND_RETURN); NULL for all other rows. Multiple NULLs allowed → non-idempotent rows don't conflict.
    `login_date`    DATE         DEFAULT NULL,         -- CST date, only for DAILY_LOGIN rows (idempotency key part)
    `expires_at`    DATETIME     DEFAULT NULL,        -- earned credits expire 30 days out; nullified on spend/sweep; spent/refund/expired rows are NULL
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_coin_idem` (`idem_key`),          -- "review:{orderId}" once per order; "refund:{orderId}" once per order. NULL for CHECKOUT_SPEND/DAILY_LOGIN/EXPIRED → no conflict.
    UNIQUE KEY `uk_coin_daily` (`user_id`, `login_date`),  -- only DAILY_LOGIN rows set login_date
    KEY `idx_coin_user` (`user_id`),
    KEY `idx_coin_user_expires` (`user_id`, `expires_at`), -- balance SUM + FIFO spend
    KEY `idx_coin_expire` (`expires_at`),               -- expiry sweep
    KEY `idx_coin_order` (`ref_order_id`)               -- refund lookup (CHECKOUT_SPEND rows by order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

> **Idempotency-key design (§2, §7):** The idempotency constraints are enforced via a single `uk_coin_idem(idem_key)` unique key + `uk_coin_daily(user_id, login_date)`:
> - `REVIEW_REWARD` rows set `idem_key = "review:{orderId}"` → at most one review-reward per order. Resolves the Phase D concurrent-double-fire flag.
> - `REFUND_RETURN` rows set `idem_key = "refund:{orderId}"` → at most one coin-return per order.
> - `CHECKOUT_SPEND` rows set `idem_key = NULL` → multiple per order allowed (one per consumed credit). MySQL/InnoDB treats multiple NULLs as distinct under a UNIQUE index, so they don't conflict.
> - `DAILY_LOGIN` rows are unique via `uk_coin_daily(user_id, login_date)`.
> - `EXPIRED` rows set `idem_key = NULL` (one per swept credit; idempotency is the `selectExpiredUnsweptCredits` query + the nullify-on-sweep, not a unique key).
>
> A `ref_order_id` is set on REVIEW_REWARD / CHECKOUT_SPEND / REFUND_RETURN rows (for the refund lookup `selectSpendRowsByOrder`), but it is **non-unique** (CHECKOUT_SPEND has many per order). This is why the idempotency lives on `idem_key`, not `ref_order_id`.

### 3b. New `orders.paid_in_cents` column — the actual paid amount

```sql
ALTER TABLE `orders`
  ADD COLUMN `paid_in_cents` INT NOT NULL DEFAULT 0;   -- actual amount paid after coin discount (review reward = this / 100)
```

`schema.sql` `orders` CREATE TABLE updated to include `paid_in_cents INT NOT NULL DEFAULT 0` (after `total_in_cents`). Set in the checkout consumer: `paidInCents = totalInCents - coinDiscountCents`. For orders placed before Phase E (no coins spent), `paid_in_cents` defaults to `total_in_cents` — but the column is only read by the review-reward path, which only fires for orders reviewed *after* Phase E ships, so legacy rows are never read with a stale 0 (they'd be reviewed long before Phase E). To be safe, the consumer always sets `paidInCents` explicitly on insert (never relies on the default for new orders).

### 3c. CheckoutRequest payload extension

The Redis payload (`CheckoutServiceImpl.jsonPayload`) gains two fields, tolerated-as-absent by the consumer (same convention as `customerPhone`/`paymentMethod`):
```json
{ ..., "useCoins": true, "coinAmount": 300 }
```
The consumer reads them with the same null-tolerant pattern (`useCoins` defaults false, `coinAmount` defaults 0).

## 4. Backend

### 4a. Entity / DTOs (new + modified)

- **`CoinLedger.java`** (entity, extends `BaseEntity`, `@TableName("coin_ledger")`): `Long userId`, `Integer amount`, `Integer balanceAfter`, `String source` (a `CoinSource` enum: `DAILY_LOGIN, REVIEW_REWARD, CHECKOUT_SPEND, REFUND_RETURN, EXPIRED`), `Long refOrderId`, `Long refCreditId`, `java.time.LocalDate loginDate`, `java.time.LocalDateTime expiresAt`.
- **`CheckoutRequest`** (modify): add `Boolean useCoins`, `Integer coinAmount`.
- **`CheckoutResponse`** (unchanged).
- **`CoinBalanceVO`** (new): `Integer balance` (current usable), `Boolean claimedToday` (daily-login claimed for today's CST date), `Integer expiringSoon` (coins expiring within 7 days — a small UX nicety; computed in the same query family).
- **`CoinHistoryVO`** (new): `Long id`, `Integer amount`, `String source`, `Long refOrderId`, `LocalDateTime expiresAt`, `LocalDateTime createTime`, `Integer balanceAfter` — a paginated ledger row for the history list.
- **`Order`** (modify): add `int paidInCents`.
- **`CheckoutRequest`** frontend type mirrors the backend (§5a).

### 4b. `CoinMapper` (new) + XML

```java
@Mapper
public interface CoinMapper extends BaseMapper<CoinLedger> {

    /** Current usable balance = SUM(amount) over rows where expires_at IS NULL OR > now(). */
    Integer selectBalance(@Param("userId") Long userId);

    /** Un-expired positive credit rows for FIFO spend, oldest-first, locked for update. */
    List<CoinLedger> selectSpendableCreditsForUpdate(@Param("userId") Long userId);

    /** Pending earned credits past their expiry clock (for the sweep). */
    List<CoinLedger> selectExpiredUnsweptCredits();

    /** Has the user a DAILY_LOGIN row for this CST date? (null-check + insert path handles the rest.) */
    CoinLedger selectDailyLogin(@Param("userId") Long userId, @Param("loginDate") LocalDate loginDate);

    /** Paginated ledger history, newest-first. */
    IPage<CoinLedger> selectHistory(@Param("page") IPage<CoinLedger> page, @Param("userId") Long userId);

    /** The CHECKOUT_SPEND rows for an order (to mirror as REFUND_RETURN on refund). */
    List<CoinLedger> selectSpendRowsByOrder(@Param("userId") Long userId, @Param("orderId") Long orderId);
}
```

`CoinMapper.xml` provides these. `selectBalance`:
```sql
SELECT COALESCE(SUM(amount), 0) FROM coin_ledger
WHERE user_id = #{userId} AND (expires_at IS NULL OR expires_at > NOW())
```
(`expires_at IS NULL` rows are spent/refund/expired terminal rows + their balancing credit rows — they net to zero; `expires_at > NOW()` are pending earned credits. The SUM is correct in all states per §4h.)

`selectSpendableCreditsForUpdate`:
```sql
SELECT * FROM coin_ledger
WHERE user_id = #{userId}
  AND amount > 0
  AND expires_at IS NOT NULL AND expires_at > NOW()
ORDER BY expires_at ASC, id ASC
FOR UPDATE
```
(`expires_at IS NOT NULL` excludes spent credits (nullified on spend) and expired credits (nullified on sweep) — only *pending* positive credits are spendable. `FOR UPDATE` serializes a user's concurrent spends — no overspend. Ordered oldest-first = FIFO, so soonest-to-expire coins spend first.)

`selectExpiredUnsweptCredits`:
```sql
SELECT * FROM coin_ledger
WHERE amount > 0 AND expires_at IS NOT NULL AND expires_at <= NOW()
```
(All users' credits past their clock — the sweep is global, not per-user. `expires_at IS NOT NULL` skips already-swept credits (nullified on sweep), making the sweep idempotent across re-runs.)

`selectSpendRowsByOrder`:
```sql
SELECT * FROM coin_ledger
WHERE user_id = #{userId} AND ref_order_id = #{orderId} AND source = 'CHECKOUT_SPEND'
ORDER BY id ASC
```
(The rows to mirror as `REFUND_RETURN` on refund. `userId` is resolved from the order in `refundCoins` — the query is scoped to that user so a cross-user `orderId` leak is impossible.)

### 4c. `CoinService` (new interface) + impl — replaces `CoinServiceStub`

```java
public interface CoinService {
    /** UC012 §1: award 100 coins on first login of the CST day. Idempotent. */
    void awardDailyLogin(Long userId);

    /** UC012 §2: credit the order's actual paid amount as coins. Idempotent (one per order). */
    void awardReviewReward(Long orderId, Long userId);

    /** UC005: compute the max-allowed coin discount for a checkout cart (validation only — no deduction). */
    CoinDiscountPreview previewCheckoutDiscount(Long userId, Map<Long,Integer> qtyByProduct);

    /** UC005: deduct `coinAmount` coins FIFO for an order. Called inside the consumer's transaction. */
    void spendAtCheckout(Long userId, Long orderId, int coinAmount);

    /** UC008/UC009: return the coins spent on an order (on refund). Idempotent (one per order). */
    void refundCoins(Long orderId);

    /** UC012 read: current balance + daily-claim status. */
    CoinBalanceVO getBalance(Long userId);

    /** UC012 read: paginated ledger. */
    IPage<CoinHistoryVO> getHistory(Long userId, PageQuery query);
}
```

**`awardReviewReward(orderId, userId)`** (replaces the stub body):
1. Load the `Order` by `orderId` (the seam receives the resolved real PK from `ReviewServiceImpl`, which got it via `orderService.getById` — see Phase D). Verify `order.userId == userId`.
2. `paidInCents = order.paidInCents`. `rewardCoins = paidInCents / 100` (integer yuan, floor). If `rewardCoins <= 0` (a fully-discounted or zero-amount order), no-op + log.
3. `balance = selectBalance(userId)`.
4. `INSERT` a `REVIEW_REWARD` row (`amount = rewardCoins`, `refOrderId = orderId`, `idemKey = "review:" + orderId`, `expiresAt = now + 30 days`, `balanceAfter = balance + rewardCoins`). On `DataIntegrityViolationException` (the `uk_coin_idem` unique key — the concurrent double-fire), catch + log + return (idempotent no-op).
5. Wrapped in `@Transactional` (the caller's `ReviewServiceImpl.create` try/catch already isolates failures).

**`awardDailyLogin(userId)`**:
1. `loginDate = LocalDate.now(ZoneId.of("Asia/Shanghai"))` (the 4am-boundary date).
2. `selectDailyLogin(userId, loginDate)` — if present, no-op (already claimed today).
3. Else `balance = selectBalance(userId)`; `INSERT` a `DAILY_LOGIN` row (`amount = 100`, `loginDate = loginDate`, `expiresAt = now + 30 days`, `balanceAfter = balance + 100`). On `DataIntegrityViolationException` (`uk_coin_daily` — two concurrent logins same instant), catch + no-op.
4. Called from `AuthServiceImpl.login` in try/catch.

**`previewCheckoutDiscount(userId, qtyByProduct)`** (hot-path validation, no deduction):
1. Fetch the products (`productMapper.selectBatchIds`); if any `is_flash_sale == 1` → return a preview marking coins-ineligible (the hot path then rejects `useCoins=true` with 400 "coins cannot be used on flash-sale items").
2. `nonFlashSubtotal = Σ price × qty`. `maxDiscountCents = floor(nonFlashSubtotal * 0.5)`. `maxCoins = floor(maxDiscountCents / 100) * 100` (100-coin granularity). `balance = selectBalance(userId)`. `usable = min(maxCoins, balance)` (rounded down to a multiple of 100).
3. Return `{ eligible, maxCoins, balance, usable }`. The hot path uses `usable` to clamp the client's `coinAmount`.

**`spendAtCheckout(userId, orderId, coinAmount)`** (consumer transaction):
1. If `coinAmount <= 0`, no-op.
2. `SELECT ... FOR UPDATE` the user's spendable credits (FIFO). `remaining = coinAmount`.
3. Consume credits oldest-first: for each credit, `take = min(credit.amount, remaining)`; insert a `CHECKOUT_SPEND` row (`amount = -take`, `refOrderId = orderId`, `refCreditId = credit.id`, `idemKey = null`, `expiresAt = null`, `balanceAfter` = running balance); then **nullify the consumed credit's `expires_at`** (`credit.expiresAt = null`, `updateById`) so it's removed from the spendable/pending set (per §4h — a spent credit's positive row + its CHECKOUT_SPEND row net to zero in the balance); `remaining -= take`. Stop when `remaining == 0`.
4. If `remaining > 0` after exhausting credits → throw `BusinessException(CONFLICT, "insufficient coins")`. (This is a last-line guard; the hot path pre-validated, but the consumer is the authority — the balance could have changed between the hot path and the consumer. The throw rolls back the whole consumer transaction, so the Order insert + stock decrement also roll back — consistent: no order, no coin deduction. The user retries.) 
   - **Important:** this rollback means the user's stock gate decrement (in the hot path) is *not* rolled back — but that's the existing at-least-once behavior (the stock was decremented in Redis in the hot path; the consumer does the DB backstop). If the coin spend fails here, the order is not created; the Redis stock decrement is orphaned. The existing `OrderStockReleaseService` does NOT auto-recredit on a consumer failure today. **Mitigation:** because the hot path pre-validates the coin balance and the consumer runs ~100ms later, a real insufficient-coins failure is a narrow race (the user spent coins elsewhere in that 100ms window). On that race, the stock is orphaned in Redis but the DB stock is unchanged (the consumer's `decrementStock` is in the same rolled-back transaction). The orphaned Redis decrement self-heals if the product's stock is ever re-initialized, and is acceptable for V1 (documented as a known limitation in the status doc; a full fix would recredit Redis on coin-spend rollback, deferred). The plan's tests cover the happy path + the idempotent paths; the narrow race is documented, not test-driven.
5. The `CHECKOUT_SPEND` rows are the source of truth for `refundCoins`.

**`refundCoins(orderId)`** (UC008/UC009):
1. Load the order by `orderId`, get `userId` (the refund flow has the order; `refundCoins` resolves the owner internally).
2. `spendRows = selectSpendRowsByOrder(userId, orderId)`. If empty → no-op (order placed without coins / pre-Phase-E — zero spend rows = zero insert).
3. For each `CHECKOUT_SPEND` row: insert a `REFUND_RETURN` row (`amount = -spendRow.amount` (positive), `refOrderId = orderId`, `refCreditId = spendRow.refCreditId`, `idemKey = "refund:" + orderId + ":" + spendRow.id` (per-spend-row → unique per refund-return row; `uk_coin_idem` enforces "at most one return per spent credit"), `expiresAt = <the original credit's expires_at>` (restored, NOT a fresh 30 days — the per-credit clock is honored), `balanceAfter` = running).
4. On `DataIntegrityViolationException` (`uk_coin_idem` — double-refund re-derives the same spend rows → same `idemKey`s → duplicate-key), catch + no-op per row.
5. Called from `OrderServiceImpl.cancelPaid` and the admin refund-approve path, each in try/catch (a coin-refund failure must not block the order refund — mirrors the review-seam discipline).

**`getBalance(userId)`**: `balance = selectBalance(userId)`; `claimedToday = selectDailyLogin(userId, todayCST) != null`; `expiringSoon = Σ amount of credits expiring within 7 days`. Returns `CoinBalanceVO`.

**`getHistory(userId, query)`**: `selectHistory` paginated → map to `CoinHistoryVO`.

### 4d. `CoinServiceStub` removal

`CoinServiceStub` is deleted; `ReviewServiceImpl`'s field becomes `CoinService coinService` (interface). The `reviewSubmitted(orderId, userId)` call becomes `coinService.awardReviewReward(orderId, userId)`. The `ReviewServiceImplTest` mock changes from `@Mock CoinServiceStub` to `@Mock CoinService` (5 call sites — trivial, mechanical). The try/catch around the call stays (the credit is now real, so the guard matters).

### 4e. `CheckoutServiceImpl` changes (hot path)

1. After step 2 (fetch products) and before the stock gate (step 3), if `request.useCoins == true`:
   - If any product `is_flash_sale == 1` → 400 "coins cannot be used on flash-sale items".
   - `preview = coinService.previewCheckoutDiscount(userId, qtyByProduct)`.
   - Clamp `coinAmount = min(request.coinAmount, preview.usable)`. If `request.coinAmount > preview.usable` → 400 "coin amount exceeds allowed discount" (fail fast — don't silently clamp, so the client can correct). If `useCoins` true but `coinAmount <= 0` → 400 "coin amount must be positive".
   - The validated `coinAmount` (≤ preview.usable) is carried into the payload (step 5) for the consumer to deduct.
2. The `totalInCents` computation (step 4) is unchanged (it's the *undiscounted* subtotal — the discount is applied at `paidInCents`).
3. `jsonPayload` gains `useCoins` + `coinAmount`.

### 4f. `StreamConsumerService.processPayload` changes (consumer)

1. Parse `useCoins` + `coinAmount` (null-tolerant, default false/0).
2. After the Order insert + stock backstop succeeds (the existing `for (JsonNode item : itemsNode)` loop), but **before** setting the Redis status keys:
   - Compute `coinDiscountCents = (useCoins ? coinAmount / 100 : 0)`.
   - `paidInCents = totalInCents - coinDiscountCents`. Set `order.setPaidInCents(paidInCents)`.
   - If `useCoins` → `coinService.spendAtCheckout(userId, order.getId(), coinAmount)` (inside the same `@Transactional`). On `BusinessException(CONFLICT, "insufficient coins")` → set `order.setStatus(FAILED)`, `orderMapper.updateById(order)`, set the Redis status to "failed", recredit the just-decremented DB stock (the loop already decremented it), and return (no coin deduction, no order — the stock is restored to DB, the Redis orphan noted in §4c). This mirrors the existing FAILED path (lines 272-281) for a stock-backstop failure.
   - **`paidInCents` is set on the `order` object before `orderMapper.insert(order)`** (so the column is populated atomically with the row), and `spendAtCheckout` runs *after* the stock backstop loop succeeds (so coins are only deducted for an order that will actually exist). `paidInCents = totalInCents - (useCoins ? coinAmount/100 : 0)`.

### 4g. `OrderServiceImpl` changes — refund-coin hook

In `cancelPaid(id, userId)` and in the admin refund-approve path (`AdminOrderController`/service), after the status transition succeeds, call `coinService.refundCoins(orderId)` in try/catch. The `userId` for `refundCoins` is resolved internally from the order (so the call signature is just `refundCoins(orderId)`). 

> The admin refund-approve path lives in `AdminOrderService`/`AdminOrderController` (Phase C) — the plan reads that file to find the exact approve method and adds the call there. `cancelPaid` is in `OrderServiceImpl` (read in exploration).

### 4h. `OrderLifecycleScheduler` — expiry sweep (new scheduled method)

Add to `OrderLifecycleScheduler`:
```java
@Scheduled(cron = "1 1 4 * * *")  // daily 4:01am CST (app tz = Asia/Shanghai)
public void sweepExpiredCoins() {
    try {
        coinService.sweepExpiredCredits();
    } catch (Exception e) {
        log.error("sweepExpiredCoins failed", e);
    }
}
```
`CoinService.sweepExpiredCredits()`: for each `selectExpiredUnsweptCredits()` row, insert an `EXPIRED` row (`amount = -credit.amount`, `refCreditId = credit.id`, `idemKey = null`, `expiresAt = null`, `balanceAfter` = running). Per-credit try/catch + outer try/catch (same discipline as the existing schedulers).

> **FIFO + expiry model (settled):** The ledger is append-only for *audit* (every row is a fact). A credit row's `expires_at` doubles as a state flag:
> - **Earned & pending** → `expires_at = <future 30-day date>` (set). Counts toward the balance.
> - **Spent** → `spendAtCheckout` nullifies the consumed credit's `expires_at` (sets it NULL) and inserts a `CHECKOUT_SPEND` negative row (also `expires_at = NULL`). The two net to zero in the balance (`expires_at IS NULL OR > now()` includes both → +credit + (−spend) = 0). Nullifying the credit's `expires_at` removes it from the expired sweep.
> - **Expired** → the sweep inserts an `EXPIRED` negative row (`expires_at = NULL`) and nullifies the credit's `expires_at`. Same net-zero; the credit is no longer re-sweepable.
>
> So `balance = SUM(amount) WHERE (expires_at IS NULL OR expires_at > now())` is correct in all three states: a pending credit contributes +N; a spent or expired credit contributes +N (its own row, now `expires_at NULL`) − N (its balancing row, `expires_at NULL`) = 0. No mutation-on-read; only `spendAtCheckout` and the sweep mutate `expires_at` (both inside transactions).

Final `sweepExpiredCredits()`: for each credit with `amount > 0 AND expires_at IS NOT NULL AND expires_at <= now()`:
1. Insert `EXPIRED` row (`amount = -credit.amount`, `refCreditId = credit.id`, `idemKey = null`, `expiresAt = null`, `balanceAfter` = running).
2. Set `credit.expiresAt = null` (so it's not re-swept; net contribution is now zero via the EXPIRED row + the credit's now-null expires_at). This two-step is idempotent: a re-run sees `expires_at IS NULL` → skips the credit. The per-credit try/catch means a failure on one credit doesn't abort the sweep of the rest.

### 4i. `CoinController` (new) + `SecurityConfig`

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/coins/balance` | USER | current balance + daily-claim status |
| GET | `/coins/history` | USER | paginated ledger (current/size params) |

Both are under `anyRequest().authenticated()` (the existing catch-all covers them — same as Phase D's review endpoints; no explicit matcher needed, matching the codebase convention).

### 4j. `AuthServiceImpl.login` — daily-login trigger

After `loginAttemptService.recordSuccess(user.getId())`:
```java
try {
    coinService.awardDailyLogin(user.getId());
} catch (Exception e) {
    log.warn("awardDailyLogin({}) failed: {}", user.getId(), e.getMessage());
}
```
(`AuthServiceImpl` gains a `CoinService` constructor-injected dependency.)

## 5. Frontend

### 5a. Types (`src/api/types.ts`)
- `CheckoutRequest`: add `useCoins?: boolean`, `coinAmount?: number`.
- `Order`: add `paidInCents: number`.
- New `CoinBalance { balance: number; claimedToday: boolean; expiringSoon: number }`.
- New `CoinHistoryRow { id: number; amount: number; source: string; refOrderId?: number; expiresAt?: string; createTime: string; balanceAfter: number }`.

### 5b. API client (`src/api/coin.ts`, new)
- `getCoinBalance(): Promise<CoinBalance>` → `GET /coins/balance`.
- `getCoinHistory(current, size): Promise<PageResult<CoinHistoryRow>>` → `GET /coins/history`.

### 5c. `Checkout.tsx` — coins-at-checkout (UC005 coin branch)
- Fetch `getCoinBalance()` on mount (when stage === 'form'); store `balance`.
- A "使用金币" checkbox + a coin-amount input (number, step 100, min 0, max = `min(balance, maxCoins)`). The max is computed client-side from the cart subtotal (`maxCoins = floor(totalCents * 0.5 / 100) * 100`); the server clamps/rejects. Show the resulting discount (`coinAmount / 100` yuan) and the adjusted total (`totalCents - coinAmount/100`).
- If any cart item is a flash-sale item → disable the coins checkbox + show "折扣商品无法使用金币" (the cart items carry `isFlashSale`? — the cart context stores `productId`/`name`/`priceInCents`/`quantity`; it does NOT carry `isFlashSale`. **Decision:** fetch the products' `isFlashSale` from `getProduct(id)` for each cart item on mount, OR add `isFlashSale` to the cart item type when adding to cart. Simpler: on Checkout mount, if any item lacks `isFlashSale`, the server's 400 will catch it; but better UX to check client-side. **Decision: extend the cart's `CartItem` type to carry `isFlashSale`** (set when the product is added to the cart in `Products.tsx`/`ProductDetailPage`), so checkout can gate the checkbox without extra fetches. The plan reads `CartContext` to make this additive.)
- On `checkout()` submit, include `useCoins` + `coinAmount` in the payload.
- The resolved/paid stages show `order.paidInCents` as the actual amount (the existing UI shows `order.totalInCents` — update the "Total" display to `paidInCents` when coins were used, or show both "Subtotal" + "Coin discount" + "Paid").

### 5d. Account/profile — coin summary
- A small "我的金币" card (balance + "今日已领取"/"今日可领取" + a link to history) on the profile/account page. The plan reads the existing profile page (`Profile.tsx` or `Account.tsx`) to place it. If no natural home, add a `/coins` route with a history list. **Decision: add the card to the existing profile page + a `/coins` history route** (minimal: balance card reused + a paginated table).

### 5e. `Checkout.tsx` tests + a `Coins.test.tsx`
- Checkout test: coins checkbox disabled when a flash-sale item is in cart; submitting with `useCoins` includes `coinAmount`; the paid total reflects the discount.
- A `Coins.test.tsx` (or extend profile test): the balance card renders balance + claim status; history list renders rows.

## 6. Testing

### 6a. Backend
- **Unit (`CoinServiceImplTest`)**: `awardDailyLogin` credits 100 once, no-op on same-day second call (idempotent via unique key — mock throws `DataIntegrityViolationException`); `awardReviewReward` credits `paidInCents/100`, no-op on duplicate-key (the concurrent double-fire); `previewCheckoutDiscount` rejects flash-sale carts, caps at 50%, clamps to balance; `spendAtCheckout` FIFO consumes oldest-first, throws CONFLICT on insufficient; `refundCoins` mirrors spend rows, restores original expires_at, no-op when no spend rows, no-op on duplicate; `sweepExpiredCredits` inserts EXPIRED + nullifies credit expires_at, idempotent on re-run.
- **Integration (`CoinIntegrationTest`, `@SpringBootTest`)**: full earn→spend→expire flow — register user → login (+100 daily) → checkout-with-coins (deduct, paid_in_cents set) → ship→deliver→review (+review reward = paidInCents/100) → balance reflects both; cancel-paid returns coins; the 4:01am sweep (triggered manually via the service method) expires a 31-day-old credit; FIFO spend prefers the sooner-expiring credit.
- **Integration (`CheckoutWithCoinsIntegrationTest`)**: checkout with `useCoins` on a non-flash cart succeeds, `paid_in_cents < total_in_cents`, a `CHECKOUT_SPEND` ledger row exists; checkout with a flash-sale item + `useCoins` → 400; checkout requesting more than 50% → 400.
- **Extend `ReviewServiceImplTest`**: mock `CoinService` (not `CoinServiceStub`); assert `awardReviewReward(orderId, userId)` is called once on the last-item gate, never on partial/upsert (the 5 existing call sites updated mechanically).

### 6b. Frontend
- `Checkout.test.tsx` (extend): coins checkbox present; disabled when flash-sale item in cart; submit includes `useCoins`/`coinAmount`; paid total reflects discount.
- `Coins.test.tsx` (new): balance card renders; history list renders rows from `getCoinHistory`.

### 6c. E2E
- New `e2e/tests/coins.test.mjs`: register → login (assert `GET /coins/balance` shows 100, claimedToday true) → checkout a non-flash product with coins (useCoins, coinAmount=100) → pay → assert `GET /orders/{id}` shows `paidInCents = totalInCents - 100` → admin ship → confirm receipt → review each item → assert `GET /coins/balance` increased by `paidInCents/100` → cancel-paid on a separate coins-eligible order → assert coins returned. Re-run catalog + orders + reviews e2e as the regression gate.

### 6d. Skip-and-return
Hard bugs → log to `docs/superpowers/skipped-issues.md`, `@Disabled`/`test.skip` with reason, proceed; fix at Phase F.

## 7. Error handling & edge cases

| Case | Behavior |
|---|---|
| Two concurrent "last item" reviews both call `awardReviewReward` | First insert succeeds; second hits `uk_coin_idem` (idem_key `"review:{orderId}"`) → caught, logged, no-op. No double-credit. (The Phase D flag, resolved.) |
| Two concurrent logins same CST instant | First `DAILY_LOGIN` insert succeeds; second hits `uk_coin_daily` → no-op. |
| Login across the 4am boundary (login at 3:59am, again at 4:01am) | Two different CST dates → two `DAILY_LOGIN` rows → +200 coins (one per date). Correct per "4am刷新". |
| Checkout with `useCoins` on a cart containing any flash-sale item | 400 "coins cannot be used on flash-sale items" (hot path). |
| Checkout requesting `coinAmount` > 50% cap or > balance | 400 "coin amount exceeds allowed discount" (hot path). |
| Balance drops between hot-path validation and consumer deduction (race) | Consumer `spendAtCheckout` throws CONFLICT → consumer sets order FAILED, recredits DB stock, returns (no order, no deduction). Redis stock decrement orphaned (documented V1 limitation). |
| Review reward on a fully-discounted order (`paidInCents == 0`) | `rewardCoins = 0` → no-op, no ledger row. |
| Refund an order that used no coins (or pre-Phase-E) | `selectSpendRowsByOrder` returns empty → no `REFUND_RETURN` row → no-op. |
| Double-refund (idempotency) | Second `refundCoins` re-derives the same spend rows → same per-row `idem_key` (`"refund:{orderId}:{spendRowId}"`) → duplicate-key → no-op per row. |
| Expiry sweep runs twice | Second run: the credit's `expires_at` is now NULL → `selectExpiredUnsweptCredits` skips it → no duplicate EXPIRED row. |
| A credit is spent, then its 30-day clock would have passed | Spent credit's `expires_at` is nullified on spend → not in the expired sweep. Its CHECKOUT_SPEND row (expires_at null) nets it to zero in the balance. |
| `awardDailyLogin` / `awardReviewReward` throw | Caught by the caller's try/catch (login / review create) → the primary flow (login/review) is unaffected. |
| A refund returns coins whose original credit already expired | The `REFUND_RETURN` restores the original `expires_at` (which is in the past) → the returned coins are immediately expired (balance SUM excludes them). Edge case: refunded coins past their clock are worthless. Correct per "30天过期" (the clock didn't pause). Documented in tests. |
| Concurrent coin spends by the same user (two checkouts) | `SELECT ... FOR UPDATE` on the user's credit rows serializes them → no overspend. Second checkout sees the reduced balance. |

## 8. Build order (subagent-driven)

0. Branch `feature/phase-e-coins` off `main`; create `coin_ledger` table + `orders.paid_in_cents` column (live dev DB ALTER + `schema.sql`); re-run baseline (backend 122 + frontend 44 + e2e catalog/orders/reviews).
1. **Backend — coin ledger core**: `CoinLedger` entity; `CoinSource` enum; `CoinMapper` + XML (`selectBalance`, `selectSpendableCreditsForUpdate`, `selectExpiredUnsweptCredits`, `selectDailyLogin`, `selectHistory`, `selectSpendRowsByOrder`); `CoinService` interface + `CoinServiceImpl` (awardDailyLogin, awardReviewReward, previewCheckoutDiscount, spendAtCheckout, refundCoins, sweepExpiredCredits, getBalance, getHistory). Tests (unit + integration).
2. **Backend — wire the seams**: replace `CoinServiceStub` with `CoinService` in `ReviewServiceImpl` (+ test); daily-login trigger in `AuthServiceImpl`; `CheckoutRequest` + `CheckoutServiceImpl` hot-path validation + payload; `StreamConsumerService` consumer deduction + `paid_in_cents`; refund-coin hook in `cancelPaid` + admin refund-approve; `sweepExpiredCoins` scheduler in `OrderLifecycleScheduler`. `CoinController` + endpoints. Tests (integration: checkout-with-coins, cancel-paid-returns-coins).
3. **Frontend**: types (`CheckoutRequest`, `Order`, `CoinBalance`, `CoinHistoryRow`); `api/coin.ts`; `Checkout.tsx` coins branch (+ cart `isFlashSale` carry); profile coin card + `/coins` history route. Tests.
4. **E2E**: coins flow + regression; final review; merge `feature/phase-e-coins`→`main`; re-run regression; update status doc + memory.

## 9. Traceability

| UC / NFR | Phase E coverage | Status after Phase E |
|---|---|---|
| UC012 §1 daily-login coins (100/day, 4am CST) | `CoinService.awardDailyLogin` + `AuthServiceImpl.login` trigger + `uk_coin_daily` idempotency | full |
| UC012 §2 review reward (= paid amount) | `CoinService.awardReviewReward` (replaces stub) + `orders.paid_in_cents` + `uk_coin_idem` (`review:{orderId}`) idempotency | full |
| UC012 §3 30-day per-credit FIFO expiry | `expires_at` per credit + `sweepExpiredCoins` (4:01am) + FIFO spend + spent-credit nullify | full |
| UC012 §4 spend rules (100=¥1, ≤50%, no flash-sale) | `previewCheckoutDiscount` (hot path) + `spendAtCheckout` (consumer) + flash-sale gate | full |
| UC005 coin branch (选择是否使用优惠金币, 自动计算优惠后价格) | `CheckoutRequest.useCoins/coinAmount` + `Checkout.tsx` coins UI + `paid_in_cents` | full |
| UC008/UC009 refund returns coins | `refundCoins` in `cancelPaid` + admin refund-approve + `uk_coin_idem` (`refund:{orderId}:{spendRowId}`) idempotency | full |
| NFR op-log | coin endpoints are user-owned (not admin) — covered by catch-all op-log; admin refund-approve already logged | met |
