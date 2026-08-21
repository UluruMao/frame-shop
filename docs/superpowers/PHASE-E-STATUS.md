# Phase E (Coins, UC012) Complete — Status Report (2026-08-21, autonomous session)

## What I did
Ran the full cycle on **Phase E (Coins, UC012)**. Design spec → implementation plan → 13-task subagent-driven build (fresh implementer per task + per-task code review + fix loops) → final whole-branch review (opus) → fix the 3 findings → merge. All under auto mode.

**Merged to `main`** (merge commit `a2e51f0`, branch `feature/phase-e-coins`, 17 commits: 2 docs + 13 implementation + 1 schema-fix + 1 final-review-fix). Working tree clean (only the untracked `use case/` dir, same as always).

## What's built (UC012 + completes UC005)
- **`coin_ledger` table** — append-only rows; a credit's `expires_at` doubles as a state flag (set = pending earned, NULL = spent/expired/terminal). Balance = `SUM(amount) WHERE (expires_at IS NULL OR expires_at > now())`. Idempotency via `uk_coin_idem(idem_key)` + `uk_coin_daily(user_id, login_date)`.
- **Three earn paths** — daily-login (100/day, 4am CST refresh, idempotent via `uk_coin_daily`), review-reward (= order's actual paid amount, `paidInCents / 100` coins, idempotent via `"review:{orderId}"`), refund-return (restores the original per-credit expiry — snapshotted on the CHECKOUT_SPEND row's `ref_credit_expires_at` column — idempotent via `"refund:{orderId}:{spendRowId}"`).
- **FIFO spend at checkout** — `SELECT ... FOR UPDATE` on the user's spendable credits (oldest-first), nullifies each consumed credit's `expires_at` (via `@TableField(updateStrategy=ALWAYS)` on the entity). `spendAtCheckout` is `@Transactional` inside the checkout consumer's transaction.
- **30-day per-credit expiry sweep** — `OrderLifecycleScheduler.sweepExpiredCoins` (`@Scheduled(cron="1 1 4 * * *")`, 4:01am CST), inserts an `EXPIRED` balancing row + nullifies the credit's `expires_at`. Idempotent (nullified credits skip the sweep).
- **Checkout coin branch (UC005)** — `CheckoutRequest.useCoins/coinAmount`; hot-path validation (`previewCheckoutDiscount`: flash-sale reject, 50%-of-undiscounted-subtotal cap, 100-coin granularity, spendable-balance clamp) fail-fast BEFORE the stock gate; consumer deducts atomically + records `orders.paid_in_cents = totalInCents - coinAmount` (1:1 coin:cent mapping, 100 coins = ¥1 = 100 cents). On insufficient-spendable, a `canSpend` pre-check sets the order FAILED + recredits stock WITHOUT an exception path (avoiding the rollback-only tx).
- **Refund-coin hooks** — `OrderServiceImpl.cancelPaid` (UC008) + `AdminOrderServiceImpl.approveRefund` (UC009) call `coinService.refundCoins(orderId)` after the stock release, each isolated by try/catch.
- **Read endpoints** — `CoinController` `GET /coins/balance` + `GET /coins/history` (JWT-derived `SecurityUtils.currentUserId()`).
- **Daily-login trigger** — `AuthServiceImpl.login` calls `awardDailyLogin` after `recordSuccess`, before the token, isolated by try/catch.
- **Frontend** — `Checkout.tsx` coins UI (checkbox + amount input, flash-gated, discount + adjusted paid total); `Coins.tsx` balance card + paginated ledger at `/coins`; `CartItem.isFlashSale` carry; `api/coin.ts` client; types.

## Tests — all green
- Backend: **154/154** (was 151; +3 from the final-review fixes' test additions).
- Frontend: **48/48** (was 44; +4 Phase E: Checkout coins + Coins page + cart-reducer + types).
- E2E coins: **11/11** (new — full UC012 lifecycle: daily-login idempotency, history, checkout-with-coins `paidInCents = total - coinAmount`, full lifecycle pay→ship→deliver→review→review-reward, refund-return, flash-sale 400 reject). Catalog + orders + reviews e2e still green.
- Pre-existing non-catalog e2e cascade: 13 SMS-gated register failures (out of scope since Phase C, Phase F cleanup) — NOT Phase E regressions.

## Final review verdict: MERGE WITH FIXES → all 3 fixed (opus)
The final whole-branch review found **two Critical + one Important** finding — all test-masked (the per-task reviews + integration tests didn't catch them; the e2e surfaced the symptoms):
1. **CRITICAL — Refunded coins permanently unspendable:** `refundCoins` restored `originalCredit.getExpiresAt()`, but `spendAtCheckout` had nullified the credit's `expires_at` → the refund restored null → REFUND_RETURN rows counted toward the balance SUM but were excluded from `selectSpendableCreditsForUpdate` (refunded coins inflated the balance but could never be re-spent). The unit test masked this (mocked the credit with a future expiry). **Fix:** new `ref_credit_expires_at` column on `coin_ledger` + `CoinLedger.refCreditExpiresAt` field; `spendAtCheckout` snapshots the credit's expiry on the spend row BEFORE nullifying; `refundCoins` restores from the spend row. Returned coins are now FIFO-spendable (spec §2: "keep their original expiry").
2. **CRITICAL — Consumer coin-failure catch in a rollback-only tx:** `spendAtCheckout` is `@Transactional REQUIRED` (joins the consumer tx); on `BusinessException(CONFLICT)` throw, Spring marked the shared tx rollback-only BEFORE the catch → the FAILED-status write + Redis recredit were discarded, the RPOP'd payload lost. The existing stock-failure FAILED path didn't have this (it returns 0, no exception). The e2e never hit it (the `selectSpendableBalance` fix made the race too narrow). **Fix:** pre-check `coinService.canSpend(userId, coinAmount)` in the consumer (a read, no tx marking) before calling `spendAtCheckout`; the common insufficient case now sets FAILED + recredits without an exception path. `spendAtCheckout`'s CONFLICT throw stays as a last-line guard for the narrow race (documented V1 limitation, spec §4c).
3. **IMPORTANT — Frontend flash-gating dead code:** `Product` entity's primitive `boolean isFlashSale` + Lombok `@Data` → Jackson serialized it as `flashSale` (is-prefix strip) → frontend read `product.isFlashSale` → undefined → cart `hasFlash` always false → coins checkbox never disabled for flash carts (backend 400 was the real gate; the UX message never showed). **Fix:** `@JsonProperty("isFlashSale")` on the `Product` field (mirrors the `ProductDTO` fix from Task 12), propagated to `ProductDetail`/`ProductListItem`.

## Two e2e-surfaced bug fixes during Task 12 (before the final review)
1. **`ProductDTO.isFlashSale` binding** — primitive `boolean isFlashSale` + Lombok `is`-prefix getter → Jackson bound `flashSale` not `isFlashSale`, so `POST /products {"isFlashSale":true}` persisted `is_flash_sale=0` → the coin flash-reject read false from the DB → accepted flash+coins. **Fix:** `@JsonProperty("isFlashSale")` on the DTO field. (Pre-existing ProductDTO bug; the coin flash-reject path exposed it.)
2. **`previewCheckoutDiscount.usable` vs `spendAtCheckout` divergence** — `usable` used the balance SUM (counts refunded/nullified credits via their REFUND_RETURN row), but `spendAtCheckout` consumes only FIFO-spendable credits (`amount>0 AND expires_at IS NOT NULL AND > now()`). Divergence → preview reported 100 usable when 38 was spendable → the consumer's `spendAtCheckout` threw CONFLICT after partial writes, marking the consumer tx rollback-only (order never persisted, 404). **Fix:** new `selectSpendableBalance` query; `usable = min(maxCoins, floor(spendable/100)*100)`. The hot path now 400s cleanly before the stock gate when spendable < coinAmount.

## The coin:cent mapping (settled)
100 coins = ¥1 = 100 cents. `paidInCents = totalInCents - coinAmount` (1:1). `awardReviewReward = paidInCents / 100`. Frontend `Checkout.tsx` `coinDiscountCents = useCoins ? coinAmount : 0` (NOT `coinAmount/100`). The plan's spec-formula `coinAmount/100` was a brief bug, caught + fixed in Task 6.

## Deferred to Phase F (logged in the SDD ledger + skipped-issues)
- **VM re-deploy of Phase E** — the VirtualBox VM still runs stale Phase-B/C code (same deferral as Phase D); Phase F redeploys with Phase D+E.
- **Pre-existing SMS-gated register e2e cascade** (13 failures) — out of scope since Phase C; Phase F resolves via an SMS-code test-profile bypass.
- **Deferred minors (none block merge):**
  - `@Scheduled` cron uses the JVM default timezone (not explicitly `Asia/Shanghai`); consistent with the existing schedulers, but if a future VM runs UTC the sweep fires at 4:01am UTC. Add `@EnableScheduling(timeZone="Asia/Shanghai")` at Phase F.
  - `ORDER_SEQ` is wall-clock-millis-seeded (collision-prone across JVM restarts) — infra debt surfaced by the e2e flakiness; Phase F hardening.
  - `CartContext.loadInitial` doesn't default `isFlashSale` for stale localStorage carts (backend flash-gate 400 catches it; self-heals on add/reconcile) — one-liner follow-up.
  - Various cosmetic: dead `import java.util.ArrayList` in `CoinServiceImpl`; test-helper `DAILY_LOGIN` source on generic credits; `CoinMapper.xml` no resultMap; `CoinDiscountPreview` lacks `VO` suffix; dead `productMapper` autowire in `CoinIntegrationTest`; coins history page pagination is first-50 with no UI controls.

## Next up: Phase F (Live chat, lazy-load, 10k QPS, cleanup)
The umbrella spec covers it: live chat (UC011, WebSocket + STOMP), image lazy-load (NFR), 10k QPS load proof, skipped-bug cleanup (the SMS-cascade + the deferred minors), final regression + VM redeploy. New branch `feature/phase-f-chat` off `main` (`a2e51f0`).

## Recovery map
- SDD ledger: `.superpowers/sdd/2026-08-21-phase-e-coins/progress.md` (per-task + review + fix history).
- Design: `docs/superpowers/specs/2026-08-21-phase-e-coins-design.md`.
- Plan: `docs/superpowers/plans/2026-08-21-phase-e-coins.md`.
- Skipped/deferred: `docs/superpowers/skipped-issues.md`.
