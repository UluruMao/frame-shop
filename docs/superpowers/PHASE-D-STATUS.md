# Phase D (Reviews, UC010) Complete — Status Report (2026-08-20, autonomous session)

## What I did
Ran the full cycle on **Phase D (Reviews, UC010)**. Design spec → implementation plan → 12-task subagent-driven build → per-task code reviews → final whole-branch review → merge. All under auto mode.

**Merged to `main`** (commit `8b44d19`, merge of branch `feature/phase-d-reviews`, 13 implementation commits). Working tree clean.

## What's built (UC010 + Phase B sort gap)
- **Per-item 3-D star reviews** — `Review` entity + `ReviewMapper` (+XML) onto the existing (migrated) `review` table. Unique key relaxed from one-review-per-order (`uk_review_order`) to one-per-`(order_id, product_id)` (`uk_review_order_product`). Three star dimensions (商品质量/物流速度/客服态度, 1-5 each), optional text content, optional images, optional anonymity, edit-tracking (`edited_at`).
- **`ReviewService`** — `create` (upsert on duplicate), `update` (any field, sets `editedAt`), `listByOrder` (ownership-checked, joins `productName` from `order_item`). Ownership-before-state on every endpoint (`orderService.getById` throws NOT_FOUND on mismatch — never reveals another user's order).
- **Once-per-order COMPLETED + coin seam** — `create` computes `wasFullyReviewed` before insert, `nowFullyReviewed` after; fires `orderService.completeOrder(orderId)` (the Phase C CAS, idempotent) + `coinServiceStub.reviewSubmitted(orderId, userId)` only on the review that completes the set. Each in its own try/catch so neither can break the write. Verified across insert/upsert/COMPLETED-state-order paths.
- **`CoinServiceStub`** — no-op `@Service` seam at the UC012 trigger point ("评价后领金币"); logs the Phase-E intent. Phase E replaces the body with the real coin ledger credit.
- **`ReviewImageService`** — jpg/png only (content-type AND extension — defense in depth), ≤20MB per file, ≤9 per review (stored as a JSON array in `images VARCHAR(2048)`). Validation runs BEFORE any file write. Multipart global limits raised 10MB→20MB / 50MB→200MB. Reuses the existing `frame.upload.dir` + `/uploads/**` pipeline (`/uploads/reviews/` subdir).
- **未评价 tab** — `OrderMapper.selectUnreviewedOrders` (correlated `EXISTS`/`NOT EXISTS` on `order_item`×`review`, scoped to `DELIVERED`/`COMPLETED` + `user_id`) + `OrderService.listUnreviewedOrders` + `GET /orders/unreviewed`. The Orders.tsx disabled "未评价 (Phase D)" tab is now active, fetches `listUnreviewedOrders()`.
- **`ReviewController`** — 4 endpoints (`GET/POST /orders/{orderId}/reviews`, `PUT /reviews/{reviewId}`, `POST /reviews/images`). Path `orderId` is authoritative over the body (`req.setOrderId(orderId)`) — prevents cross-order spoofing. SecurityConfig untouched (catch-all `anyRequest().authenticated()` covers them; `/uploads/reviews/**` by existing `GET /uploads/**` permitAll).
- **Frontend** — `ReviewModal` (3 `StarRating` rows + textarea + image uploader with ≤9 cap + previews/remove + 匿名 checkbox) + `StarRating` component + `api/review.ts` client + types. Orders.tsx per-item 写评价/编辑评价 button in the drawer (DELIVERED/COMPLETED orders) → opens the modal prefilled on edit; `onSuccess` re-fetches + reloads. Products.tsx fills the Phase B gap (`sales|asc` + `rating|asc`).
- **Rating-sort** — Phase B's live `AVG((quality+logistics+service)/3.0)` JOIN now has real review data feeding it; a regression test (`ProductMapperIntegrationTest.ratingSort_reflectsNewReview`) guards it.

## Tests — all green
- Backend: **122/122** (was 104; +18 — ReviewMapperIntegrationTest 2, ReviewServiceImplTest 6, ReviewImageServiceImplTest 6, ReviewIntegrationTest 3, ProductMapperIntegrationTest rating-sort test 1). 0 skipped.
- Frontend: **44/44** (was 38; +6 — ReviewModal.test 3, Orders.test +2, Products.test +1).
- E2E: reviews suite **6/6** (new) — full UC010 lifecycle: checkout→pay→ship→confirm-receipt→DELIVERED→appears in 未评价→review→COMPLETED + leaves 未评价→edit→edited=true. Catalog 7/7 + orders 11/11 still green.
- Pre-existing e2e failures: 13 auth-SMS-cascade + seed-mismatch (out of scope since Phase C, Phase F). NOT Phase D regressions.

## Final review verdict: MERGE (opus)
Clean — no must-fix. Verified the once-per-order COMPLETED+coin gate across insert/upsert/COMPLETED-state paths; ownership-before-state on all review endpoints; image validation ordering (no write before validation); `selectUnreviewedOrders` SQL correctness; frontend↔backend type alignment (`ReviewVO`↔`Review`, `@JsonProperty("edited")`); SecurityConfig catch-all coverage; path-authoritative `orderId` override. The single Important finding (concurrent last-item coin-seam double-fire under concurrency) is a no-op today (the stub does nothing) and is a Phase-E design responsibility — Phase E MUST implement the coin credit idempotently (unique constraint on `order_id` in the coin ledger), not as a fire-and-forget. Logged for Phase E.

## Infrastructure event during Phase D
The dev `frame_db` was fully rebuilt mid-Phase-D: all 7 tables were InnoDB Error-194 corrupt ("Tablespace is missing") — a consequence of the iNode-client crash + Windows system-restore (the restore orphaned InnoDB tablespace metadata across the whole data dictionary, not just `user`). `mysqldump` impossible (no table readable). User authorized a full `DROP DATABASE frame_db` + recreate from `schema.sql` (self-contained: DDL + admin seed + product seeds). All 6 Phase-D tables now CHECK status OK; admin/demo seeds restored; 122/122 backend green. `demo_product` table (a template-era table, not in `schema.sql`, not used by frame-shop) is absent — expected.

## Deferred to Phase F (logged in the SDD ledger + skipped-issues)
- **VM re-deploy** — VirtualBox + the local Linux VM survived the system restore (still active), but runs stale Phase-B/C code. After Phase D merge, a re-deploy is needed to surface UC010 on the VM. (Phase F.)
- **Pre-existing non-catalog e2e failures** (auth SMS-gated register cascade → 401s + `list products contains Demo Gadget` seed mismatch) — out of scope since Phase C; resolve at Phase F VM re-deploy + an SMS-code test-profile bypass.
- **Non-blocking observations (deferred minors, none block merge):**
  - ReviewMapperIntegrationTest: count-only assertion on `selectByOrderId`; unused `LocalDateTime` import; FQNs in `@Autowired` (brief-inherited).
  - ReviewServiceImpl: `applyCreateToExisting` sets stars unconditionally (latent NPE if `ReviewCreateRequest` ever relaxes `@NotNull` — currently safe); unused `eq` import in test.
  - ReviewImageServiceImplTest: `upload_png_ok` double-upload + dead `|| .PNG` branch; no positive 20MB-exactly boundary test (impl uses strict `>`, correct).
  - `OrderServiceImpl.listUnreviewedOrders` has no unit test (covered by integration + e2e).
  - `GET /orders/unreviewed` declared after `GET /orders/{id}` (safe under Spring MVC PathPattern literal-first precedence, undocumented).
  - ReviewModal tests: Test1 doesn't inspect create payload; Test2 name claims prefill but only asserts the call; × remove button lacks aria-label.
  - Orders.tsx: `reviewsByOrder` cache unbounded; `onSuccess` review re-fetch after `await load()` (brief label-flip window).
  - ReviewServiceImpl `fromImagesJson` degrades corrupted JSON to empty list (with `log.warn`).
  - `ReviewImageServiceImpl` leaves a partial file on disk if `Files.copy` throws mid-copy (matches existing avatar/product-upload convention).

## Next up: Phase E (Coins, UC012)
The umbrella spec covers it: daily sign-in coin (100 coins, 4am CST refresh), review-reward coins (order's actual paid amount — the `CoinServiceStub.reviewSubmitted` seam is already wired from Phase D), 30-day expiry per-acquisition, 100-coins=¥1 discount ≤50% of undiscounted amount, not usable on discounted items. Phase D left the exact trigger point + try/catch guard; Phase E builds the coin ledger table + the real credit. New branch `feature/phase-e-coins` off `main`.

## Recovery map
- SDD ledger: `.superpowers/sdd/2026-08-20-phase-d-reviews/progress.md` (detailed per-task + review + fix + DB-rebuild history).
- Design: `docs/superpowers/specs/2026-08-20-phase-d-reviews-design.md`.
- Plan: `docs/superpowers/plans/2026-08-20-phase-d-reviews.md`.
- Skipped/deferred: `docs/superpowers/skipped-issues.md`.
