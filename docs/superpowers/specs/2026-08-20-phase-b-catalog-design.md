# Design — Frame Shop Phase B (Catalog): UC000

> Date: 2026-08-20. Phase B turns the flash-sale-only product list into a real catalog: keyword search (fuzzy, multi-keyword), multi-criteria sort (price / sales / rating, asc + desc), category filter, and a product detail page. Built on `main` (HEAD `9a29e54`, Phase A merged) on a new `feature/phase-b-catalog` branch.
> Covers UC000 of `use case/use cases.txt` (lines 6–12). Guests (unregistered users) can browse/sort/search per the role rules (lines 1–4).
> This phase sits under the umbrella design `2026-08-19-frame-shop-uc-fulfillment-design.md` §4; this document is the detailed Phase B spec.

## 1. Scope (locked)

- **UC000 — full**: browse, sort (price/sales/rating asc+desc), keyword search (fuzzy, multi-keyword split on comma/space, half-match), "暂无数据" when empty, special-char-safe (no crash).
- **Category filter**: wire the existing-but-ignored `category` field (umbrella spec §4 says "keep category").
- **Product detail page**: new `/products/:id` (currently no detail page exists; `getProduct(id)` is used only for live-stock polling).
- **Guest access**: `/products` and `/products/:id` are **public** (moved out of `RequireAuth`); add-to-cart still requires login.
- **`review` table created now** (table only, no write endpoints) so the rating `AVG(stars)` join is real — Phase D adds review-writing endpoints onto the same table, no rework. Phase B rating-sorts by 0 (no reviews exist yet → sorts last in desc).
- **NFR touch-points**: 防SQL注入 (parameterized binds + whitelist sort), 图片懒加载 (`loading="lazy"` on all product images). HTTPS/op-log unchanged (read-only GETs aren't logged; correct).

### Out of scope (deferred)
- Review *writing* (create/edit/image-upload) → Phase D.
- Real first-screen ≤1.5s audit + 10k QPS proof → Phase F (Phase B applies `loading="lazy"` as it builds).
- OAuth2 → deferred (umbrella spec §10).

## 2. Decisions (this phase)

| Decision | Choice | Why |
|---|---|---|
| Backend query approach | **A — custom XML `<select>`** with LEFT JOIN aggregations | One code path; `LambdaQueryWrapper` can't express the sales/rating joins; matches umbrella spec's "computed via join, not denormalized column" |
| Sales scope (which statuses count as sold) | **PAID only** today (filter `orders.status='PAID'`) | "Sold" = actually purchased; PENDING/CANCELLED/FAILED excluded. Phase C widens the IN-list to `('PAID','SHIPPED','DELIVERED','COMPLETED')` when it adds those statuses |
| Query params location | **Extend `PageQuery`** with `keyword`/`category` (reusing existing `sortField`/`sortOrder`) | Fewer classes; other endpoints ignore the extra fields; frontend `PageQuery` type mirrors. One set of sort fields, not two |
| Flash-sale grid vs catalog | **Evolve `Products.tsx` into a catalog**; flash-sale countdown/live-stock-poll moves into the detail page | Cleaner UX matches UC000 "于主界面浏览商品"; single browse entry point |
| Review table timing | **Create now (table only)**; rating sorts by 0 | Real AVG join now; Phase D adds write endpoints on the same table — no rework |
| Category filter | **Wire now** | Low cost; table already has column + `idx_product_category`; completes UC000 browse intent |
| Guest routing | **Move `/products` + `/products/:id` out of `RequireAuth`** | UC000 role rule: guests browse/search/sort; add-to-cart stays protected |
| E2E branch merge | **Fold `e2e-test-suite`→`main` into Phase B step 0** | Umbrella spec §2 Phase 0 requires it; provides the 26-test regression baseline. If it conflicts hard → skip-and-return (log + proceed with local suites) |

## 3. Data model

### 3a. New table: `review` (created now, table-only)

Phase D's schema pulled forward (umbrella spec §6). Phase B creates the table only — no `Review` entity/mapper/service/endpoints. Rating sort reads `AVG((quality+logistics+service)/3.0)` from it; zero rows → NULL → coalesced to 0.

```sql
CREATE TABLE review (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id        BIGINT NOT NULL,
  user_id         BIGINT NOT NULL,
  product_id      BIGINT NOT NULL,
  quality_stars   TINYINT NOT NULL,   -- 1-5
  logistics_stars TINYINT NOT NULL,
  service_stars   TINYINT NOT NULL,
  content         TEXT,
  create_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_review_order (order_id),       -- one review per order
  INDEX idx_review_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

Phase D adds the `Review` entity, `ReviewMapper`, and write endpoints onto this exact table/columns — no migration then.

### 3b. Existing tables — no schema change

- `product`: `category` + `idx_product_category` already present. No denormalized sales/rating columns.
- `order_item`: `idx_order_item_product` already present. `quantity` summed for sales.
- `orders`: `status` column stores `OrderStatus` enum names; today filtered to `'PAID'`.

## 4. Backend

### 4a. DTOs

- `ProductListItem` (read projection, not an entity): all `Product` fields **plus** `sold` (int) and `avgRating` (double). List endpoint returns `PageResult<ProductListItem>`.
- `ProductDetail`: `Product` fields **plus** `sold` + `avgRating`. Returned by the detail endpoint.

### 4b. Query params — extend `PageQuery`

`PageQuery` gains `keyword` (String) and `category` (String). The existing `sortField`/`sortOrder` fields become the **live** sort params (previously declared-but-ignored): `sortField` accepts `price`/`sales`/`rating` (validated to a whitelist), `sortOrder` accepts `asc`/`desc`. No new `sortBy` field is added — reusing `sortField`/`sortOrder` avoids two competing sort concepts. Existing `current`/`size` unchanged. Frontend `PageQuery` type mirrors these additions (adding `keyword?`/`category?`).

### 4c. Custom XML query (Approach A)

Declared on `ProductMapper` (`@Param("q")` is the extended `PageQuery` itself — no separate `ProductPageQuery` class):
```java
IPage<ProductListItem> pageProducts(IPage<?> page, @Param("q") PageQuery q);
ProductDetail findDetailById(@Param("id") Long id);
List<String> selectDistinctCategories();
```

`ProductMapper.xml` — `pageProducts`:
```xml
<select id="pageProducts" resultType="com.iflytek.frame.dto.ProductListItem">
  SELECT p.id, p.name, p.price_in_cents, p.stock, p.image_url, p.category,
         p.is_flash_sale, p.flash_sale_at, p.description, p.create_time, p.update_time,
         COALESCE(s.sold, 0) AS sold,
         COALESCE(r.avg_rating, 0) AS avg_rating
  FROM product p
  LEFT JOIN (
    SELECT oi.product_id, SUM(oi.quantity) AS sold
    FROM order_item oi
    JOIN orders o ON o.id = oi.order_id
    WHERE o.status = 'PAID'
    GROUP BY oi.product_id
  ) s ON s.product_id = p.id
  LEFT JOIN (
    SELECT product_id, AVG((quality_stars + logistics_stars + service_stars) / 3.0) AS avg_rating
    FROM review GROUP BY product_id
  ) r ON r.product_id = p.id
  <where>
    <if test="q.category != null and q.category != ''">AND p.category = #{q.category}</if>
    <if test="q.keywords != null and q.keywords.size() > 0">
      <foreach collection="q.keywords" item="kw" separator="AND">AND p.name LIKE CONCAT('%', #{kw}, '%')</foreach>
    </if>
  </where>
  ORDER BY
  <choose>
    <when test="q.sortField == 'price'">p.price_in_cents</when>
    <when test="q.sortField == 'sales'">sold</when>
    <when test="q.sortField == 'rating'">avg_rating</when>
    <otherwise>p.create_time</otherwise>
  </choose>
  ${q.sortOrderSql}
</select>
```

`findDetailById`: same two LEFT JOINs filtered `WHERE p.id = #{id}` (returns one `ProductDetail`).

`selectDistinctCategories`: `SELECT DISTINCT category FROM product WHERE category IS NOT NULL ORDER BY category`.

**SQL-injection safety (NFR 防SQL注入):**
- Keyword terms bound via `#{kw}` (parameterized) → `<`, `'`, `%`, `;--` are literal, never crash, never inject.
- Sort field: fixed `<choose>` whitelist — no user string interpolated.
- Sort order: validated in the service to exactly `ASC`/`DESC` (default `DESC`) → passed as `${q.sortOrderSql}` (a service-derived constant, never raw user input).

### 4d. Service / controller

- `ProductServiceImpl.page(PageQuery q)`:
  - Normalize `sortField` → must be `price`/`sales`/`rating`, else `null` (→ `<choose>` defaults to `create_time`).
  - Normalize `sortOrder` → `asc`/`desc` (case-insensitive), else default `desc`; set `q.sortOrderSql = " ASC"` or `" DESC"` (a service-derived constant, not raw user input).
  - Split `keyword` on `[,\s]+`, trim, drop empties → `q.keywords` list (null if blank). `keywords` and `sortOrderSql` are transient derived fields on `PageQuery` (set by the service, not deserialized from request).
  - Build `Page<>` from `current`/`size`; call `mapper.pageProducts(page, q)`; return `PageResult.of(...)`.
- `ProductServiceImpl.getDetail(Long id)`: `mapper.findDetailById(id)`; if null → throw not-found (existing `ResourceNotFoundException` pattern).
- `ProductController`:
  - `GET /products` → `page(@Valid @ParameterObject PageQuery q)` (unchanged signature; `PageQuery` now carries the new fields).
  - `GET /products/{id}` → returns `ProductDetail` (extended; was `Product`).
  - `GET /products/categories` → `List<String>` (public).

## 5. Frontend

### 5a. Routing (React Router v6)
- Move `/products` and `/products/:id` **out** of `RequireAuth` to public routes (guests browse). Keep `/checkout`, `/orders`, `/profile`, `/admin` protected.
- Add `/products/:id` → `ProductDetailPage`.
- `MainLayout` nav still shows "Products" for everyone; add-to-cart click from a guest session redirects to `/login`.

### 5b. `Products.tsx` → catalog
- **Search bar**: text input; submit (Enter / button) → `pageProducts({ keyword, category, sortBy, sortOrder, current, size })`.
- **Sort control**: dropdown — 按价格 / 按销量 / 按评价, plus an asc/desc toggle. Maps to `sortBy` + `sortOrder`.
- **Category filter**: dropdown populated from `listCategories()`; "全部" → no category filter.
- **Result grid**: responsive grid of **catalog cards** (image, name, price, sold, avg-rating badge, "查看详情" → `/products/:id`).
- **Flash-sale behavior** moves off the list card; the detail page handles countdown/live-stock. (Non-flash products show static stock on the card.)
- **Empty state**: `records.length === 0` → "暂无数据".
- **Pagination**: prev/next; `size` default 12.
- `loading="lazy"` on all product images.

### 5c. `ProductDetailPage` (`/products/:id`)
- `getProductDetail(id)` → `ProductDetail` (product fields + `sold` + `avgRating`).
- Renders: description, image, stock, price, sold, avg rating.
- **Flash-sale products**: countdown (if `flashSaleAt` future) or live-stock polling (if past) — logic lifted from the current `FlashSaleCard`; non-flash shows static stock.
- **Reviews section**: placeholder ("暂无评价" / reviews arrive in Phase D).
- **Add-to-cart**: if logged in → existing cart flow; if guest → redirect `/login`.
- Not-found (`/products/999999`) → "商品不存在" state.
- `loading="lazy"` on images.

### 5d. API client (`src/api/`)
- `PageQuery` type: add `keyword?`, `category?`, `sortBy?` (`'price'|'sales'|'rating'`), `sortOrder?` (`'asc'|'desc'`).
- `pageProducts(query)`: forward new params.
- New `getProductDetail(id)` → `GET /products/{id}` → `ProductDetail`; new `listCategories()` → `GET /products/categories`. (Keeps the existing `getProduct(id)` for the live-stock poll, now called from the detail page.)
- New `ProductDetail` type in `types.ts`.

## 6. Testing

### 6a. Backend (JUnit + Mockito)
- Extend `ProductServiceImplTest` (unit): `page` sortField normalization + keyword split + sortOrder normalization (garbage never reaches SQL); `getDetail` not-found.
- New `ProductMapperIntegrationTest` (`@SpringBootTest` + real MariaDB + Redis): seed products/orders/order_items/reviews; assert keyword LIKE + multi-keyword AND + no-match empty + special-char safety (`"<script>'%;--"` → 200, empty, no crash) + sales sort (PAID counts, PENDING/CANCELLED don't) + rating sort (reviewed > no-review; no-review sorts last) + category filter + `getDetail` returns sold/avgRating.

### 6b. Frontend (Vitest + Testing Library)
- Rewrite `Products.test.tsx`: search submits keyword, sort dropdown sets `sortBy`/`sortOrder`, category filter applies, empty → "暂无数据", cards link to `/products/:id`.
- New `ProductDetailPage.test.tsx`: renders detail; flash-sale countdown + live-stock-poll assertions (moved from old `Products.test.tsx`); guest add-to-cart → redirect `/login`; not-found state.

### 6c. E2E (extend `e2e/` Node suite)
- Step 0: merge `e2e-test-suite`→`main` (Phase 0 baseline). Re-run the 26 existing tests green. If hard conflict → skip-and-return (log to `skipped-issues.md`, proceed with local suites as the gate).
- New UC000 e2e: guest `GET /products` 200 + paginated; keyword filtered; special-char → 200 empty no crash; sort price/sales/rating asc+desc ordered; category filter; empty → `records:[]`. Runs against local dev backend (VM redeploy deferred to Phase F).

### 6d. Skip-and-return
Any hard bug → log to `docs/superpowers/skipped-issues.md`, mark test `@Disabled`/`test.skip` with reason, proceed; fix at Phase F.

## 7. Error handling & edge cases

| Case | Behavior |
|---|---|
| Empty/blank keyword | No `LIKE` filter (returns all subject to other filters) |
| Keyword special chars `<>'";%--` | Bound as literal parameter → no-match or literal-match; never crash, never inject |
| Multi-keyword trailing commas/spaces `"a,, b "` | Split + trim → `["a","b"]`; empties dropped |
| `sortBy` not in `{price,sales,rating}` | Falls back to `createTime` |
| `sortBy`/`sortOrder` null/blank | Defaults: `sortBy=createTime`, `sortOrder=desc` |
| No products match | `records:[]`, `total:0` → "暂无数据" |
| Zero sales / zero reviews | `sold=0`, `avgRating=0.0`; sorts deterministically (COALESCE) |
| Detail for nonexistent product | Backend 404 (Result not-found code); frontend "商品不存在" |
| Empty category dropdown | "全部" → no category filter |
| Guest clicks add-to-cart | Redirect `/login` |
| Pagination out of range (`current=999`) | `records:[]`, `total` unchanged — no error |

## 8. Build order (subagent-driven)

0. **Phase 0 baseline**: merge `e2e-test-suite`→`main`; re-run the 26 existing e2e tests green. Then branch `feature/phase-b-catalog` off `main` (so the branch carries the e2e baseline). Skip-and-return on a hard merge conflict (log to `skipped-issues.md`, proceed with local suites as the gate).
1. Backend: `review` table DDL (schema.sql + migration); `ProductListItem` + `ProductDetail` DTOs; extend `PageQuery` (`keyword`, `category`, live `sortField`/`sortOrder`); `ProductMapper` Java + XML (`pageProducts`, `findDetailById`, `selectDistinctCategories`); `ProductService` normalization + `getDetail`; controller endpoints. Tests (unit + integration).
2. Frontend: routing change (public `/products` + `/products/:id`); evolve `Products.tsx` → catalog; new `ProductDetailPage`; API client changes; tests.
3. E2E: add UC000 flow; re-run full suite (regression gate).
4. Code review (subagent); fix findings; merge `feature/phase-b-catalog`→`main`; re-run e2e regression.

## 9. Traceability

| UC / NFR | Phase B coverage | Status after Phase B |
|---|---|---|
| UC000 search/sort/browse | keyword LIKE + price/sales/rating sort asc/desc + category + "暂无数据" + special-char-safe | full |
| UC000 detail page | new `/products/:id` | full |
| UC000 guest access | public `/products`, `/products/:id` | full |
| (partial UC013) admin stock/price | already exists (Admin CRUD); unchanged | unchanged |
| NFR 防SQL注入 | parameterized binds + whitelist sort; audit note | met (new code) |
| NFR 图片懒加载 | `loading="lazy"` on catalog/detail images | begun (audit at Phase F) |
| NFR perf (10k QPS) | not addressed | Phase F |
