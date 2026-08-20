# Design — Frame Shop Phase D (Reviews): UC010

> Date: 2026-08-20. Branch `feature/phase-d-reviews` off `main` (HEAD `2764138`, Phase C merged). Detailed spec under the umbrella `2026-08-19-frame-shop-uc-fulfillment-design.md` §6; this document is the detailed Phase D spec. Covers **UC010** only, plus filling the deferred Phase B sort gap.
>
> UC010 (用户评价): a registered, signed-in user who has **signed for** an order (order `DELIVERED`) reviews each line item: 3-dimension star ratings (商品质量 / 物流速度 / 客服态度, 1–5 each), optional text content, optional images (jpg/png ≤20MB), and may edit the review after submitting. Per `use case/use cases.txt:124-133`.

## 1. Scope (locked)

### In scope — core UC010 write-flow
- A registered user who has signed for an order (status `DELIVERED`, or `COMPLETED` via the 7-day auto-complete but still has unreviewed items — see §3 decision) can review **each line item** in that order: 3-dimension star ratings (1–5 each), optional text, optional images (jpg/png, ≤20MB per file, up to 9).
- Submitting the **first** review for an order transitions the order `DELIVERED → COMPLETED` (review-triggered completion, converging on the same `completeOrder` CAS the Phase C scheduler uses).
- Reviews are **fully editable** after submit — any field, any number of times. Only the `DELIVERED→COMPLETED` transition fires once (CAS `rows==0` is a clean no-op on repeats).
- The **未评价** tab lights up: lists the user's orders in `{DELIVERED, COMPLETED}` that still have ≥1 unreviewed line item.

### In scope — adjacent
- Fill the Phase B gap: add `sales|asc` + `rating|asc` to the `Products.tsx` sort dropdown (backend already supports all six combos; UI surfaces only 4 today).

### Out of scope (deferred)
- **Coin reward (UC012)** — Phase D stubs the trigger (`coinServiceStub.reviewSubmitted(...)` no-op + documented Phase-E hook); Phase E implements the coin ledger credit of the order's paid amount.
- **Product-detail review list (read side)** — Phase D writes reviews and they feed the catalog rating-sort; no public "list reviews for a product" endpoint/UI is built. (Catalog already shows `avgRating`; a reviews-list page is a later nicety.)
- **`completeOrder` `completed_at` atomic-write fix** (Phase C cosmetic note) — left as-is; the CAS still guards the transition.

## 2. Decisions (locked)

| Decision | Choice | Why |
|---|---|---|
| Review granularity | One review per `(order_id, product_id)` — migrate `uk_review_order` → `uk_review_order_product(order_id, product_id)` | Matches the line-items drawer UX (each `OrderItem` row gets its own 评价); gives true per-product ratings feeding the catalog rating-sort. The one-review-per-order uniqueness was a Phase B placeholder. |
| Edit-after-submit | Fully editable, anytime, any field (stars + text + images) | Spec: "系统允许评价结束后再修改评价" — read literally, the whole review is mutable. Only the `DELIVERED→COMPLETED` transition is one-shot. |
| Images | Up to 9 per review, jpg/png only, 20MB **per file**; stored as a JSON array in one `images` VARCHAR(2048) column | Spec: ≤20MB (read per-file); 9-image cap is the e-commerce norm. JSON-in-a-column avoids a child `review_image` table at Phase D's scale (YAGNI). |
| Image storage | Reuse `frame.upload.dir` + `/uploads/**` static handler + public GET; new `reviews/` subdir; new `POST /reviews/images` upload endpoint mirroring the existing avatar/product upload convention | Matches the two existing upload endpoints exactly; keeps review create/update as clean JSON bodies (no multipart+JSON mix). |
| Image validation | `isEmpty` → 400; `size > 20MB` → 400; content-type ∈ `{image/jpeg, image/png}` **and** filename ext ∈ `{.jpg,.jpeg,.png}` | The existing avatar/product endpoints do **none** of this; the spec explicitly restricts jpg/png ≤20MB. Defense in depth (type + extension). |
| Reviewable order states | `DELIVERED` **or** `COMPLETED` (if still has unreviewed items) | UC010 precondition is 已签收 (= DELIVERED). But the Phase C 7-day auto-complete scheduler can flip a DELIVERED order to COMPLETED before the user reviews. Restricting to DELIVERED-only would dead-end those orders (can never review). Allowing COMPLETED-with-unreviewed-items avoids that, and the COMPLETED transition CAS no-ops cleanly. |
| COMPLETED trigger | First-review-that-completes-all-items → `orderService.completeOrder(orderId)`; the 7-day `autoCompleteOrders` scheduler stays as the fallback | Both paths converge on the same CAS-guarded `completeOrder`; no double-transition possible. Reviewing is the *primary* completion path; the scheduler is the safety net. |
| Coin trigger | Stub `coinServiceStub.reviewSubmitted(orderId, userId)` no-op bean, called once per order when its last item gets its first review; try/catch so it can't break the write | Respects the phase boundary (coins = Phase E); leaves a clean documented seam at exactly the UC012 trigger point. |
| 未评价 source | A dedicated query `GET /orders/unreviewed`, not a status filter | 未评价 ≠ a single `OrderStatus`; it's "orders with ≥1 unreviewed item", a join condition. A dedicated endpoint keeps the tab semantics honest. |

## 3. Data model

### 3a. `review` table — migrate + extend (additive; applied to `schema.sql` + the live dev DB inline, same convention as Phase B/C)

```sql
-- Relax one-review-per-order → one review per (order, product)
ALTER TABLE review DROP INDEX uk_review_order;
ALTER TABLE review ADD UNIQUE KEY uk_review_order_product (order_id, product_id);

-- New columns: image URLs, anonymity, edit tracking
ALTER TABLE review
  ADD COLUMN images       VARCHAR(2048) DEFAULT NULL,  -- JSON array of /uploads/... URLs (≤9)
  ADD COLUMN is_anonymous TINYINT(1)   NOT NULL DEFAULT 0,
  ADD COLUMN edited_at    DATETIME      DEFAULT NULL;  -- set on any edit-after-submit
```

`schema.sql` `review` CREATE TABLE updated to match (fresh DBs match the live one):

```sql
CREATE TABLE review (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  order_id        BIGINT       NOT NULL,
  user_id         BIGINT       NOT NULL,
  product_id      BIGINT       NOT NULL,
  quality_stars   TINYINT      NOT NULL,
  logistics_stars TINYINT      NOT NULL,
  service_stars   TINYINT      NOT NULL,
  content         TEXT,
  images          VARCHAR(2048) DEFAULT NULL,
  is_anonymous    TINYINT(1)    NOT NULL DEFAULT 0,
  edited_at       DATETIME      DEFAULT NULL,
  create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_review_order_product (order_id, product_id),
  KEY idx_review_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 3b. Multipart limits — `application.yml`

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 20MB      # was 10MB — spec per-file limit
      max-request-size: 200MB  # was 50MB — room for 9×20MB + form
```

Per-endpoint `MultipartFile.getSize() > 20MB` check still enforces the rule regardless of the global limit (defense in depth).

### 3c. No new tables

Reviews reuse the extended `review` table + the existing `frame.upload.dir` / `/uploads/**` static pipeline.

## 4. Backend

### 4a. Entities / DTOs (new)

- **`Review.java`** (entity, extends `BaseEntity`, `@TableName("review")`): `Long orderId`, `Long userId`, `Long productId`, `Integer qualityStars`, `Integer logisticsStars`, `Integer serviceStars`, `String content`, `String images` (JSON), `Boolean isAnonymous`, `LocalDateTime editedAt`.
- **`ReviewCreateRequest`** (`@Valid`): `Long orderId` (@NotNull), `Long productId` (@NotNull), `Integer qualityStars` (@NotNull @Min(1) @Max(5)), `Integer logisticsStars` (same), `Integer serviceStars` (same), `String content` (optional, @Size(max=1000)), `List<String> imageUrls` (optional, @Size(max=9)), `Boolean isAnonymous` (default false).
- **`ReviewUpdateRequest`**: same shape **minus** `orderId`/`productId` (immutable keys); all other fields optional on edit.
- **`ReviewVO`** (response): full review fields + `String productName` (joined snapshot from `order_item.name`) + `Boolean edited` (derived from `editedAt != null`).

### 4b. `ReviewMapper` (new) + XML

```java
int insert(Review r);
int updateById(Review r);                                          // MyBatis-Plus style, reused for edits
Review selectByOrderAndProduct(Long orderId, Long productId);      // "already reviewed?"
List<Review> selectByOrderId(Long orderId);                        // "which items are reviewed?"
int countByOrderId(Long orderId);                                  // (not strictly needed; listByOrder suffices)
```

`ReviewMapper.xml` provides these. (The 未reviewed-orders query lives in `OrderMapper` — §4d.)

### 4c. `ReviewService` (new) + impl

```java
ReviewVO create(ReviewCreateRequest req, Long userId);
ReviewVO update(Long reviewId, ReviewUpdateRequest req, Long userId);
List<ReviewVO> listByOrder(Long orderId, Long userId);
```

**`create` flow** (the heart of Phase D, `@Transactional`):
1. Load `Order` by `orderId`; verify ownership (`o.getUserId().equals(userId)`) else `NOT_FOUND` (ownership-before-state, Phase C convention — never leak that another user's order exists).
2. Verify state ∈ `{DELIVERED, COMPLETED}` else `409 CONFLICT` ("cannot review: order not delivered"). (Refunded/cancelled/etc. → 409.)
3. Verify `productId` is in the order's `order_item` rows (else `400` "product not in this order").
4. **Upsert:** `selectByOrderAndProduct(orderId, productId)`:
   - If exists → treat as an edit: route to the `update` path (update mutable fields, set `editedAt`), then skip to the return (step 6). This honors "fully editable anytime" — a second submit for the same `(order, product)` just edits rather than erroring. Because a review already existed for this item, the order was *already* fully reviewed or still isn't; either way the COMPLETED/coin gate in step 5 doesn't re-fire.
   - If absent → `insert` a new review row. Set `images` = JSON serialization of `imageUrls` (or null if empty). Then proceed to step 5.
5. **First-review-that-completes-all-items → COMPLETED + coin seam:** Before the insert (step 4), compute `wasFullyReviewed = (count of this order's existing reviews) >= (count of distinct order_item.product_id)`. After the insert, re-check `nowFullyReviewed`. If `!wasFullyReviewed && nowFullyReviewed` (i.e. **this** review was the one that completed the set — exactly once), then:
   - call `orderService.completeOrder(orderId)`. The CAS (`updateStatusIf DELIVERED→COMPLETED`) is idempotent: if already COMPLETED (or the scheduler raced), `rows==0` no-op.
   - call `coinServiceStub.reviewSubmitted(orderId, userId)` (the UC012 seam), wrapped in try/catch so it can't break the review write.
   This gating guarantees the coin stub + COMPLETED-trigger fire **exactly once per order lifetime** — never on edits, never on upsert-of-already-reviewed, never on a re-submit. The `wasFullyReviewed` before-check is the guard that makes upsert safe.
6. Return the `ReviewVO`.

**`update` flow:** ownership check (review's `userId` matches), then `updateById` on the mutable fields (`qualityStars`/`logisticsStars`/`serviceStars`/`content`/`images`/`isAnonymous`), set `editedAt = now()`. Images are a full replace (not append) of the `images` JSON array — simpler, matches "any field editable." Does NOT re-trigger COMPLETED or coin stub (already fired on first full-review).

> **Read-side:** Phase D defines `listByOrder` (used by the drawer to show which items are already reviewed + their values for the edit case) but does **not** build a public product-detail reviews list endpoint/UI (deferred). The catalog rating-sort already consumes the `review` table directly via the existing `ProductMapper.xml` AVG JOIN.

### 4d. `OrderMapper` / `OrderService` additions

`OrderMapper` (new method + XML):

```sql
-- 未评价 tab: orders in {DELIVERED, COMPLETED} with ≥1 unreviewed line item
SELECT o.* FROM orders o
WHERE o.user_id = #{userId}
  AND o.status IN ('DELIVERED','COMPLETED')
  AND EXISTS (
    SELECT 1 FROM order_item oi
    WHERE oi.order_id = o.id
      AND NOT EXISTS (
        SELECT 1 FROM review r
        WHERE r.order_id = o.id AND r.product_id = oi.product_id
      )
  )
ORDER BY o.create_time DESC
```

```java
List<Order> selectUnreviewedOrders(Long userId);
```

`OrderService.listUnreviewedOrders(Long userId)` — thin wrapper (no pagination; per-user order count is small in dev). Exposed via `GET /orders/unreviewed`.

### 4e. `ReviewController` (new) + image upload endpoint — all USER-auth, ownership-checked

| Method | Path | Purpose |
|---|---|---|
| GET | `/orders/{orderId}/reviews` | list reviews for an order (drawer's edit-existing view) |
| POST | `/orders/{orderId}/reviews` | create/upsert a review for a `(order, product)` |
| PUT | `/reviews/{reviewId}` | edit an existing review |
| GET | `/orders/unreviewed` | the 未评价 tab |
| POST | `/reviews/images` | upload one image (jpg/png ≤20MB) → returns `{ url: "/uploads/reviews/{filename}" }` |

**Image upload** — new `ReviewImageService.upload(MultipartFile, Long userId)` (or fold into `ReviewService`):
- `file.isEmpty()` → 400 "file is empty"
- `file.getSize() > 20 * 1024 * 1024` → 400 "image exceeds 20MB"
- content-type check: `file.getContentType()` ∈ `{image/jpeg, image/png}` **and** filename extension ∈ `{.jpg, .jpeg, .png}` → else 400 "only jpg/png allowed"
- filename: `review_{userId}_{UUID}.{ext}` under `frame.upload.dir/reviews/`; `Files.createDirectories`; `file.transferTo(...)`.
- return `/uploads/reviews/{filename}`.

The frontend uploads images first (gets URLs), then sends `imageUrls` in the create/update JSON. (Matches the avatar/product pattern; no cleanup-on-abandon for Phase D — YAGNI, same as existing endpoints.)

### 4f. `CoinServiceStub` (new, no-op seam)

```java
@Service
@Slf4j
public class CoinServiceStub {
    /**
     * Called once per order when its last line item receives its first review.
     * Phase E will implement: credit the order's actual paid amount as coins
     * to the user's coin balance (UC012: 用户评价后可领取等同于本订单实际付款金额的优惠金币).
     */
    public void reviewSubmitted(Long orderId, Long userId) {
        log.info("reviewSubmitted(orderId={}, userId={}) — coin credit is Phase E (UC012)", orderId, userId);
    }
}
```

### 4g. `SecurityConfig`

Add under `anyRequest().authenticated()` (after the existing `/orders/**` user rules): `GET /orders/{orderId}/reviews`, `POST /orders/{orderId}/reviews`, `PUT /reviews/{reviewId}`, `GET /orders/unreviewed`, `POST /reviews/images`. `/uploads/reviews/**` is already covered by the existing `GET /uploads/**` `permitAll()`.

### 4h. Review-triggered COMPLETED

Covered in `create` step 5/§4c. No scheduler change; the Phase C `autoCompleteOrders` (7-day `delivered_at`) remains the fallback. Both call `orderService.completeOrder`.

## 5. Frontend

### 5a. Types (`src/api/types.ts`)
- `Review` / `ReviewVO`: `{ id, orderId, productId, productName?, qualityStars, logisticsStars, serviceStars, content?, imageUrls: string[], isAnonymous, editedAt? }`
- `ReviewCreateRequest`: `{ orderId, productId, qualityStars, logisticsStars, serviceStars, content?, imageUrls?, isAnonymous? }`
- `ReviewUpdateRequest`: `{ qualityStars?, logisticsStars?, serviceStars?, content?, imageUrls?, isAnonymous? }`

### 5b. API client (`src/api/review.ts`, new)
- `listOrderReviews(orderId): Promise<Review[]>`
- `createReview(orderId, req): Promise<Review>`
- `updateReview(reviewId, req): Promise<Review>`
- `listUnreviewedOrders(): Promise<Order[]>`
- `uploadReviewImage(file): Promise<string>` (FormData → returns URL)

### 5c. `Orders.tsx` — 未评价 tab + review entry
- The disabled `{ label: '未评价 (Phase D)', disabled: true }` tab (lines 29–36) becomes active: `{ label: '未评价', unreviewed: true }`. When this tab is active, fetch via `listUnreviewedOrders()` instead of `getMyOrders(status)`. The other tabs stay status-filtered (`getMyOrders`).
- In the line-items drawer (lines 339–352): for orders where review is allowed (`status === 'DELIVERED' || status === 'COMPLETED'`), each `OrderItem` `<li>` row gains a **写评价** / **编辑评价** button (label depends on whether a review for `(orderId, productId)` already exists — fetched via `listOrderReviews(orderId)` when the drawer expands). Each `OrderItem` already carries `productId` on the TS type, so the button can pass `(orderId, productId)`.
- The button opens **`ReviewModal.tsx`** (new, `src/components/`):
  - 3 star-rating rows (商品质量 / 物流速度 / 客服态度), 1–5, clickable stars (reuse a small `StarRating` subcomponent).
  - Textarea for `content`.
  - Image uploader: file input (`accept=".jpg,.jpeg,.png"`, `multiple`); each selected file → `uploadReviewImage(file)` → collect returned URLs; preview thumbnails with a remove (×) button; enforce ≤9 client-side.
  - 匿名 checkbox for `isAnonymous`.
  - On open: if editing (a review exists), prefill stars/content/images/anonymous from the existing review.
  - Submit → `createReview` (or `updateReview` if editing); on success, close modal + refresh `listOrderReviews` (and, if the 未评价 tab is active, refresh `listUnreviewedOrders`).

### 5d. `Products.tsx` — fill Phase B sort gap (lines 60–66)
Add the two missing options:
```jsx
<option value="sales|asc">销量升序</option>
<option value="rating|asc">评价升序</option>
```
No other change — the `onSort` handler (lines 35–38) already splits `value` into `sortField`/`sortOrder` and the backend already supports all six combos.

## 6. Testing

### 6a. Backend
- **Unit (`ReviewServiceImplTest`)**: create inserts + sets stars; upsert-on-duplicate edits instead of 409; ownership mismatch → NOT_FOUND; non-DELIVERED/COMPLETED state → 409; `productId` not in order → 400; first-review-that-completes-all-items calls `completeOrder` (verify via mock) and triggers the coin stub exactly once; partial review (some items unreviewed) does NOT complete; COMPLETED-with-unreviewed-items still accepts a review; update sets `editedAt`; image validation (oversize/bad-type/bad-ext → 400).
- **Integration (`ReviewIntegrationTest`, `@SpringBootTest` + real MariaDB + Redis)**: full flow — checkout→pay→ship→confirm-receipt→review each item→assert order COMPLETED; review of a COMPLETED-but-unreviewed order works; catalog rating-sort reflects the new avg after a review (the existing `ProductMapper` JOIN); 未评价 tab query returns the right orders (DELIVERED with unreviewed items; empty once all reviewed); image upload writes a file under `uploads/reviews/` and returns a servable URL.
- **Mapper test**: `selectUnreviewedOrders` correctness across the DELIVERED/COMPLETED + unreviewed matrix.

### 6b. Frontend
- `ReviewModal.test.tsx` (new): 3 star rows render + are clickable; image uploader accepts jpg/png, rejects >9, shows previews, remove works; create calls `createReview` with the right payload; edit prefills existing values and calls `updateReview`; submit closes modal.
- `Orders.test.tsx` (extend): 未评价 tab fetches `listUnreviewedOrders`; drawer shows 写评价/编辑评价 per item for DELIVERED/COMPLETED orders; opening the modal from an item prefills on edit.
- `Products.test.tsx` (extend): all 6 sort options present (the 4 existing + the 2 new).

### 6c. E2E
- New `e2e/tests/reviews.test.mjs`: a user+admin flow — checkout→pay→admin ship→confirm-receipt→write a review for each item→assert order COMPLETED; edit the review→assert updated; verify the catalog rating-sort shows the product with the new avg; 未评价 tab shows the order before review and hides it after. Runs against the local Phase D backend.
- Regression: re-run catalog e2e (7) + orders e2e (11) + backend (104) + frontend (38+) as the per-phase gate.

### 6d. Skip-and-return
Hard bugs → log to `docs/superpowers/skipped-issues.md`, `@Disabled`/`test.skip` with reason, proceed; fix at Phase F.

## 7. Error handling & edge cases

| Case | Behavior |
|---|---|
| Review another user's order | Ownership check before state → `404 NOT_FOUND` ("order not found") — never reveal existence. |
| Review a non-`{DELIVERED,COMPLETED}` order (e.g. REFUNDED) | `409 CONFLICT` ("cannot review: order not delivered"). |
| `productId` not in the order's items | `400` "product not in this order". |
| Duplicate create for the same `(order, product)` | Upsert → edits the existing review (sets `editedAt`), never 409. |
| Review the last unreviewed item of a COMPLETED order | `completeOrder` CAS no-ops (already COMPLETED); review still written. |
| Reviewer ≠ review owner on `PUT /reviews/{reviewId}` | `404 NOT_FOUND` (ownership-before-mutate). |
| Image > 20MB | `400` "image exceeds 20MB". |
| Image not jpg/png (by content-type or extension) | `400` "only jpg/png allowed". |
| >9 images on create/update | `@Size(max=9)` → `400`. |
| Scheduler auto-completes an order between review-create and the `completeOrder` call | CAS `rows==0` no-op; the review write already committed in the same `@Transactional` block. |
| Coin stub throws | try/catch swallows; review write unaffected (the stub is a no-op today, but the guard is forward-looking for Phase E). |
| Edit-after-submit on an already-COMPLETED order | Allowed; only `editedAt` + fields update; no re-transition, no re-coin. |

## 8. Build order (subagent-driven)

0. Branch `feature/phase-d-reviews` off `main`; re-run baseline (backend 104 + frontend 38 + catalog e2e 7 + orders e2e 11).
1. **Backend — schema + entities + mappers**: migrate `review` unique key + new columns; raise multipart limits; `Review` entity; `ReviewMapper` + XML; `OrderMapper.selectUnreviewedOrders` + XML. Tests (mapper/integration seed).
2. **Backend — service + upload + stub + coin seam**: `ReviewService`/impl (create upsert + COMPLETED trigger + coin stub call); `ReviewImageService` (jpg/png ≤20MB validation); `CoinServiceStub`; `ReviewController` + endpoints; `SecurityConfig` rules. Tests (unit + integration).
3. **Frontend**: types; `api/review.ts`; `ReviewModal.tsx` + `StarRating`; `Orders.tsx` 未评价 tab + per-item review button; `Products.tsx` sort gap. Tests.
4. **E2E**: reviews flow + regression; final review; merge `feature/phase-d-reviews`→`main`; re-run regression; update status doc + memory.

## 9. Traceability

| UC / NFR | Phase D coverage | Status after Phase D |
|---|---|---|
| UC010 用户评价 (3-D stars + text + images + edit-after-submit) | `Review` entity/mapper/service/controller + `ReviewModal` + 未评价 tab | full |
| UC010 COMPLETED trigger | `create` calls `completeOrder` when all items reviewed | full (coin = Phase E) |
| UC012 coin reward (评价后领金币) | `CoinServiceStub.reviewSubmitted` no-op seam | stubbed (Phase E implements) |
| UC000 sort both directions | `Products.tsx` adds `sales|asc` + `rating|asc` | full (closes Phase B gap) |
| NFR op-log | review endpoints are user-owned (not admin) — no op-log needed; admin image mgmt unchanged | met |
