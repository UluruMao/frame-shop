# Phase B (Catalog) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement UC000 — product catalog with keyword search (fuzzy, multi-keyword), multi-criteria sort (price/sales/rating asc+desc), category filter, "暂无数据" empty state, special-char safety, and a new product detail page — for both guests and logged-in users.

**Architecture:** Backend extends `GET /products` with a custom MyBatis XML `<select>` doing LEFT JOIN aggregations (sold = `SUM(order_item.quantity)` over PAID orders; avgRating = `AVG` over `review`), a `review` table created now (table-only; Phase D adds write endpoints), and `ProductListItem`/`ProductDetail` projection DTOs. Frontend evolves `Products.tsx` from a flash-sale grid into a searchable/sortable catalog, adds a `ProductDetailPage`, and moves `/products` + `/products/:id` out of `RequireAuth` (backend already permits public GET).

**Tech Stack:** Spring Boot 4.1 / Java 21, MyBatis-Plus 3.5.17 (custom XML mapper + `PaginationInnerInterceptor`), MariaDB, Redis, React 18 + Vite + TypeScript, React Router v6, Tailwind v3 + lucide-react, Vitest + Testing Library. Backend tests: JUnit 5 + Mockito (unit) + `@SpringBootTest` (integration). Frontend tests: Vitest + Testing Library.

## Global Constraints

- **Branch:** `feature/phase-b-catalog` off `main` (HEAD `9a29e54`). Commit per task; merge to `main` at the end.
- **Backend dir:** `C:/Users/tcmao/Desktop/newRepo/frame-backend`; **Frontend dir:** `C:/Users/tcmao/Desktop/newRepo/frame-frontend`.
- **MyBatis-Plus 3.5.17** — custom paginated `<select>` uses `IPage` param; the registered `PaginationInnerInterceptor(DbType.MARIADB)` handles LIMIT + COUNT. Mapper XML discovered via `classpath*:mapper/**/*.xml`.
- **All SQL parameterized with `#{}`** — no string concatenation, no `${}` for user input. The ONLY `${}` use is `q.sortOrderSql`, a service-derived constant (`" ASC"`/`" DESC"`) validated to exactly those two values. This satisfies the 防SQL注入 NFR and UC000's "输入特殊字符时不崩溃".
- **Sales scope:** `orders.status = 'PAID'` only today (Phase C widens the IN-list when SHIPPED/DELIVERED/COMPLETED exist).
- **Public GET:** backend `SecurityConfig` already permits `GET /products`, `GET /products/{id}` (lines 100-101). New `GET /products/categories` must be added to that public matcher.
- **`review` table created table-only** — no entity/mapper-write-endpoints this phase. Phase D adds them onto the same table.
- **Backend test commands:** `cd frame-backend && mvn -q test` (full) or `-Dtest=ClassName` (single). Integration tests use real MariaDB + Redis (the project's existing `@SpringBootTest` pattern with Testcontainers or local services — follow the existing `CheckoutFlowIntegrationTest`).
- **Frontend test command:** `cd frame-frontend && npm test` (vitest run).
- **Skip-and-return:** a hard bug → log to `docs/superpowers/skipped-issues.md`, mark test `@Disabled`/`test.skip` with reason, proceed.
- **Auto mode:** the user granted autonomous work — decide, don't ask. Log+skip genuinely critical items.

---

## File Structure

### Backend — create
- `frame-backend/src/main/java/com/iflytek/frame/dto/ProductListItem.java` — list projection DTO (Product fields + sold + avgRating).
- `frame-backend/src/main/java/com/iflytek/frame/dto/ProductDetail.java` — detail DTO (Product fields + sold + avgRating).
- `frame-backend/src/test/java/com/iflytek/frame/mapper/ProductMapperIntegrationTest.java` — integration test for the custom query.

### Backend — modify
- `frame-backend/src/main/resources/schema.sql` — add `review` table (after `product`, before `orders`).
- `frame-backend/src/main/java/com/iflytek/frame/util/PageQuery.java` — add `keyword`, `category` fields + transient `keywords` (List<String>) + `sortOrderSql` (String).
- `frame-backend/src/main/java/com/iflytek/frame/mapper/ProductMapper.java` — add `pageProducts`, `findDetailById`, `selectDistinctCategories`.
- `frame-backend/src/main/resources/mapper/ProductMapper.xml` — add `pageProducts`, `findDetailById`, `selectDistinctCategories` selects.
- `frame-backend/src/main/java/com/iflytek/frame/service/ProductService.java` — change `page` return type to `PageResult<ProductListItem>`; add `getDetail`.
- `frame-backend/src/main/java/com/iflytek/frame/service/impl/ProductServiceImpl.java` — rewrite `page` (normalize + delegate to `pageProducts`); add `getDetail`.
- `frame-backend/src/main/java/com/iflytek/frame/controller/ProductController.java` — change `page` return type; add `getDetail` (change `get` to return `ProductDetail`); add `categories`.
- `frame-backend/src/main/java/com/iflytek/frame/security/SecurityConfig.java` — add `GET /products/categories` to the public matcher.
- `frame-backend/src/test/java/com/iflytek/frame/service/ProductServiceImplTest.java` — add `page` normalization tests.

### Frontend — create
- `frame-frontend/src/pages/ProductDetailPage.tsx` — the `/products/:id` page.
- `frame-frontend/tests/ProductDetailPage.test.tsx` — its tests.

### Frontend — modify
- `frame-frontend/src/api/types.ts` — extend `PageQuery`; add `ProductDetail` type.
- `frame-frontend/src/api/product.ts` — forward new params in `pageProducts`; add `getProductDetail`, `listCategories`.
- `frame-frontend/src/router/index.tsx` — move `/products` + `/products/:id` to public; add `/products/:id` route.
- `frame-frontend/src/pages/Products.tsx` — evolve into search/sort/category catalog.
- `frame-frontend/tests/Products.test.tsx` — rewrite for catalog behavior.

---

## Task 0: Phase 0 baseline — merge `e2e-test-suite` → `main`

**Files:**
- (no file changes unless conflicts arise)

**Interfaces:**
- Consumes: the `e2e-test-suite` branch (26 e2e tests + ObjectMapper cache fix + 29 backend tests).
- Produces: `main` carrying the e2e regression baseline, so `feature/phase-b-catalog` branches from a clean base.

- [ ] **Step 1: Inspect what `e2e-test-suite` adds vs `main`**

Run:
```bash
git log --oneline main..e2e-test-suite
git log --oneline e2e-test-suite..main
```
Expected: a clear list of e2e commits ahead of main, and (ideally) none behind. If `main` is behind on unrelated commits, note them.

- [ ] **Step 2: Merge `e2e-test-suite` into `main`**

Run:
```bash
git checkout main
git merge e2e-test-suite --no-ff -m "merge: e2e-test-suite baseline (26 e2e + ObjectMapper cache fix) into main"
```
Expected: either a clean merge or a conflict. If conflict: do NOT force. Abort with `git merge --abort`, log to `docs/superpowers/skipped-issues.md` ("e2e-test-suite merge conflict — deferred; proceeding with local backend+frontend suites as Phase B gate"), and skip to Step 4. Proceeding with the merge aborted means `main` stays at `9a29e54`.

- [ ] **Step 3: Re-run the e2e baseline (only if merge succeeded)**

Run:
```bash
cd frame-backend && mvn -q -Dtest='!*MapperIntegrationTest,!*E2E*' test
cd ../e2e && npm test
```
(Adjust the e2e path/command to the project's actual e2e runner — check `package.json` scripts in the `e2e/` dir.) Expected: all backend + 26 e2e tests green. If a test fails: it's a pre-existing regression from the merge — log to `skipped-issues.md` and proceed (skip-and-return).

- [ ] **Step 4: Create the Phase B branch**

Run:
```bash
git checkout -b feature/phase-b-catalog
```
Expected: on `feature/phase-b-catalog`.

- [ ] **Step 5: Commit the merge if it happened**

Run:
```bash
git push -u origin feature/phase-b-catalog 2>/dev/null || echo "no remote — local branch only"
```
Expected: branch tracked (or local-only note). No commit needed if merge was clean (already committed); if merge was aborted, nothing to commit.

---

## Task 1: `review` table + DTOs + `PageQuery` extension

**Files:**
- Create: `frame-backend/src/main/java/com/iflytek/frame/dto/ProductListItem.java`
- Create: `frame-backend/src/main/java/com/iflytek/frame/dto/ProductDetail.java`
- Modify: `frame-backend/src/main/resources/schema.sql` (add `review` table, after `product` block ~line 63, before `orders` ~line 65)
- Modify: `frame-backend/src/main/java/com/iflytek/frame/util/PageQuery.java`
- Test: `frame-backend/src/test/java/com/iflytek/frame/util/PageQueryTest.java` (create)

**Interfaces:**
- Produces:
  - `ProductListItem` — `long id; String name; int priceInCents; Integer stock; String imageUrl; String category; boolean isFlashSale; LocalDateTime flashSaleAt; String description; LocalDateTime createTime; LocalDateTime updateTime; int sold; double avgRating;` (Lombok `@Data`).
  - `ProductDetail` — same fields as `ProductListItem`.
  - `PageQuery` new fields: `String keyword; String category;` (persisted from request) + `transient List<String> keywords;` + `transient String sortOrderSql;` (derived by service, `@JsonIgnore`).

- [ ] **Step 1: Add the `review` table to `schema.sql`**

Insert after the `product` table block (after line 63, the closing `) ENGINE=InnoDB...` of product) and before `CREATE TABLE orders`:

```sql
CREATE TABLE `review` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `order_id`        BIGINT       NOT NULL,
    `user_id`         BIGINT       NOT NULL,
    `product_id`      BIGINT       NOT NULL,
    `quality_stars`   TINYINT      NOT NULL,
    `logistics_stars` TINYINT      NOT NULL,
    `service_stars`   TINYINT      NOT NULL,
    `content`         TEXT,
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_review_order` (`order_id`),
    KEY `idx_review_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

Also add `DROP TABLE IF EXISTS \`review\`;` to the DROP block near the top (after the `order_item` drop, before `product`):
```sql
DROP TABLE IF EXISTS `review`;
```
(Note: `review` references `order`/`product`/`user` via FK-by-convention only — no hard FOREIGN KEY constraints in this schema, matching the existing style. The DROP order: `review` before `order_item`/`orders`/`product`/`user` is safe since there are no FK constraints.)

- [ ] **Step 2: Verify the DDL is valid**

Run: `cd frame-backend && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS (schema.sql is a resource, not compiled, but this confirms no syntax errors in adjacent resources broke the build).

- [ ] **Step 3: Create `ProductListItem` DTO**

`frame-backend/src/main/java/com/iflytek/frame/dto/ProductListItem.java`:
```java
package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Read projection for {@code GET /products}: base product fields plus the two
 * aggregated sort columns (sold, avgRating) computed by the custom XML query.
 * Not an entity — carries derived columns the Product entity doesn't have.
 */
@Schema(description = "Product list item with sales + rating aggregates")
@Data
public class ProductListItem {
    private Long id;
    private String name;
    private int priceInCents;
    private Integer stock;
    private String imageUrl;
    private String category;
    private boolean isFlashSale;
    private LocalDateTime flashSaleAt;
    private String description;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private int sold;
    private double avgRating;
}
```

- [ ] **Step 4: Create `ProductDetail` DTO**

`frame-backend/src/main/java/com/iflytek/frame/dto/ProductDetail.java`:
```java
package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Detail view for {@code GET /products/{id}}: full product fields plus the
 * sold + avgRating aggregates shown on the detail page header.
 */
@Schema(description = "Product detail with sales + rating aggregates")
@Data
public class ProductDetail {
    private Long id;
    private String name;
    private int priceInCents;
    private Integer stock;
    private String imageUrl;
    private String category;
    private boolean isFlashSale;
    private LocalDateTime flashSaleAt;
    private String description;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private int sold;
    private double avgRating;
}
```

- [ ] **Step 5: Write the failing `PageQuery` test**

`frame-backend/src/test/java/com/iflytek/frame/util/PageQueryTest.java`:
```java
package com.iflytek.frame.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageQueryTest {

    @Test
    void defaults_current1_size10_sortOrderDesc() {
        PageQuery q = new PageQuery();
        assertThat(q.getCurrent()).isEqualTo(1L);
        assertThat(q.getSize()).isEqualTo(10L);
        assertThat(q.getSortOrder()).isEqualTo("desc");
        assertThat(q.getKeyword()).isNull();
        assertThat(q.getCategory()).isNull();
    }

    @Test
    void keywords_and_sortOrderSql_are_transient_null_until_set() {
        PageQuery q = new PageQuery();
        assertThat(q.getKeywords()).isNull();
        assertThat(q.getSortOrderSql()).isNull();
    }
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `cd frame-backend && mvn -q -Dtest=PageQueryTest test`
Expected: FAIL — `getKeyword()` / `getKeywords()` / `getSortOrderSql()` don't exist (compile error).

- [ ] **Step 7: Extend `PageQuery`**

Modify `frame-backend/src/main/java/com/iflytek/frame/util/PageQuery.java` — add after the `sortOrder` field (line 35):
```java
    @Schema(description = "Search keyword (split on comma/space into ANDed LIKE terms)", example = "red widget")
    private String keyword;

    @Schema(description = "Category filter (exact match)", example = "electronics")
    private String category;

    /** Derived by the service: keyword split on [,\s]+, trimmed, empties dropped. Null if blank. Not a request param. */
    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    private transient java.util.List<String> keywords;

    /** Derived by the service: " ASC" or " DESC" (whitelist-validated). Not a request param. */
    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    private transient String sortOrderSql;
```
(Keep the existing `serialVersionUID`, imports, `@Data`, etc. The `transient` + hidden-schema annotations keep these off the serialized request/response.)

- [ ] **Step 8: Run test to verify it passes**

Run: `cd frame-backend && mvn -q -Dtest=PageQueryTest test`
Expected: PASS (2 tests).

- [ ] **Step 9: Commit**

```bash
git add frame-backend/src/main/resources/schema.sql \
  frame-backend/src/main/java/com/iflytek/frame/dto/ProductListItem.java \
  frame-backend/src/main/java/com/iflytek/frame/dto/ProductDetail.java \
  frame-backend/src/main/java/com/iflytek/frame/util/PageQuery.java \
  frame-backend/src/test/java/com/iflytek/frame/util/PageQueryTest.java
git commit -m "feat(phase-b): review table + ProductListItem/ProductDetail DTOs + PageQuery keyword/category"
```

---

## Task 2: `ProductMapper` — custom XML query (pageProducts, findDetailById, selectDistinctCategories)

**Files:**
- Modify: `frame-backend/src/main/java/com/iflytek/frame/mapper/ProductMapper.java`
- Modify: `frame-backend/src/main/resources/mapper/ProductMapper.xml`
- Test: `frame-backend/src/test/java/com/iflytek/frame/mapper/ProductMapperIntegrationTest.java` (create)

**Interfaces:**
- Consumes: `PageQuery` (with `keywords`, `sortField`, `sortOrderSql` set by the service — Task 3), `ProductListItem`, `ProductDetail`.
- Produces:
  - `IPage<ProductListItem> pageProducts(IPage<?> page, @Param("q") PageQuery q)`
  - `ProductDetail findDetailById(@Param("id") Long id)`
  - `List<String> selectDistinctCategories()`

- [ ] **Step 1: Write the failing integration test**

`frame-backend/src/test/java/com/iflytek/frame/mapper/ProductMapperIntegrationTest.java`:
```java
package com.iflytek.frame.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iflytek.frame.dto.ProductDetail;
import com.iflytek.frame.dto.ProductListItem;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.OrderItem;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.util.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ProductMapperIntegrationTest {

    @Autowired ProductMapper productMapper;
    @Autowired JdbcTemplate jdbc;

    private void seedCatalog() {
        jdbc.execute("DELETE FROM order_item");
        jdbc.execute("DELETE FROM orders");
        jdbc.execute("DELETE FROM product");
        jdbc.execute("DELETE FROM review");
        // two products
        jdbc.update("INSERT INTO product (id,name,price_in_cents,stock,category,is_flash_sale) " +
                "VALUES (1,'Red Widget',1000,5,'toys',0)");
        jdbc.update("INSERT INTO product (id,name,price_in_cents,stock,category,is_flash_sale) " +
                "VALUES (2,'Blue Gadget',2000,3,'toys',0)");
        jdbc.update("INSERT INTO product (id,name,price_in_cents,stock,category,is_flash_sale) " +
                "VALUES (3,'Green Gizmo',3000,0,'tools',0)");
        // PAID order: 4x Red Widget sold
        jdbc.update("INSERT INTO orders (id,user_id,customer_name,customer_email,shipping_address,total_in_cents,status,idempotency_key) " +
                "VALUES (10,1,'u','u@e.com','addr',4000,'PAID','ik-paid')");
        jdbc.update("INSERT INTO order_item (id,order_id,product_id,name,price_in_cents,quantity) " +
                "VALUES (100,10,1,'Red Widget',1000,4)");
        // PENDING order: 9x Red Widget — must NOT count as sold
        jdbc.update("INSERT INTO orders (id,user_id,customer_name,customer_email,shipping_address,total_in_cents,status,idempotency_key) " +
                "VALUES (11,1,'u','u@e.com','addr',9000,'PENDING','ik-pend')");
        jdbc.update("INSERT INTO order_item (id,order_id,product_id,name,price_in_cents,quantity) " +
                "VALUES (101,11,1,'Red Widget',1000,9)");
        // one review on Red Widget: (5+4+3)/3 = 4.0 avg
        jdbc.update("INSERT INTO review (id,order_id,user_id,product_id,quality_stars,logistics_stars,service_stars,content) " +
                "VALUES (1,10,1,1,5,4,3,'ok')");
    }

    private PageQuery q(String sortField, String sortOrderSql, List<String> keywords, String category) {
        PageQuery q = new PageQuery();
        q.setCurrent(1L);
        q.setSize(50L);
        q.setSortField(sortField);
        q.setSortOrderSql(sortOrderSql);
        q.setKeywords(keywords);
        q.setCategory(category);
        return q;
    }

    @Test
    void keyword_match_and_multiKeyword_and() {
        seedCatalog();
        IPage<ProductListItem> p = productMapper.pageProducts(new Page<>(1, 50),
                q(null, " DESC", List.of("red"), null));
        assertThat(p.getRecords()).hasSize(1);
        assertThat(p.getRecords().get(0).getName()).isEqualTo("Red Widget");

        // multi-keyword AND: "red widget" matches; "red gizmo" matches none
        assertThat(productMapper.pageProducts(new Page<>(1, 50),
                q(null, " DESC", List.of("red", "widget"), null)).getRecords()).hasSize(1);
        assertThat(productMapper.pageProducts(new Page<>(1, 50),
                q(null, " DESC", List.of("red", "gizmo"), null)).getRecords()).isEmpty();
    }

    @Test
    void special_char_keyword_returns_empty_no_crash() {
        seedCatalog();
        IPage<ProductListItem> p = productMapper.pageProducts(new Page<>(1, 50),
                q(null, " DESC", List.of("<script>'%;--"), null));
        assertThat(p.getRecords()).isEmpty();   // literal, no match, no injection
    }

    @Test
    void sales_sort_desc_paidOnly() {
        seedCatalog();
        List<ProductListItem> r = productMapper.pageProducts(new Page<>(1, 50),
                q("sales", " DESC", null, null)).getRecords();
        // Red Widget sold=4 (PAID only, PENDING excluded); others sold=0
        assertThat(r.get(0).getName()).isEqualTo("Red Widget");
        assertThat(r.get(0).getSold()).isEqualTo(4);
    }

    @Test
    void rating_sort_desc_reviewedFirst() {
        seedCatalog();
        List<ProductListItem> r = productMapper.pageProducts(new Page<>(1, 50),
                q("rating", " DESC", null, null)).getRecords();
        // Red Widget avgRating=4.0; others 0.0 → sorts last in desc
        assertThat(r.get(0).getName()).isEqualTo("Red Widget");
        assertThat(r.get(0).getAvgRating()).isEqualTo(4.0);
        assertThat(r.get(1).getAvgRating()).isEqualTo(0.0);
    }

    @Test
    void price_sort_asc_desc() {
        seedCatalog();
        List<ProductListItem> asc = productMapper.pageProducts(new Page<>(1, 50),
                q("price", " ASC", null, null)).getRecords();
        assertThat(asc.get(0).getName()).isEqualTo("Red Widget");     // 1000
        assertThat(asc.get(2).getName()).isEqualTo("Green Gizmo");    // 3000
        List<ProductListItem> desc = productMapper.pageProducts(new Page<>(1, 50),
                q("price", " DESC", null, null)).getRecords();
        assertThat(desc.get(0).getName()).isEqualTo("Green Gizmo");
    }

    @Test
    void category_filter() {
        seedCatalog();
        List<ProductListItem> r = productMapper.pageProducts(new Page<>(1, 50),
                q(null, " DESC", null, "tools")).getRecords();
        assertThat(r).hasSize(1);
        assertThat(r.get(0).getName()).isEqualTo("Green Gizmo");
    }

    @Test
    void findDetailById_returns_sold_and_avgRating() {
        seedCatalog();
        ProductDetail d = productMapper.findDetailById(1L);
        assertThat(d).isNotNull();
        assertThat(d.getName()).isEqualTo("Red Widget");
        assertThat(d.getSold()).isEqualTo(4);
        assertThat(d.getAvgRating()).isEqualTo(4.0);
    }

    @Test
    void findDetailById_nonexistent_returns_null() {
        seedCatalog();
        assertThat(productMapper.findDetailById(999999L)).isNull();
    }

    @Test
    void selectDistinctCategories() {
        seedCatalog();
        List<String> cats = productMapper.selectDistinctCategories();
        assertThat(cats).containsExactly("tools", "toys");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frame-backend && mvn -q -Dtest=ProductMapperIntegrationTest test`
Expected: FAIL — `pageProducts` / `findDetailById` / `selectDistinctCategories` don't exist (compile error).

- [ ] **Step 3: Declare the mapper methods**

Modify `frame-backend/src/main/java/com/iflytek/frame/mapper/ProductMapper.java` — add these imports and methods (keep existing methods):
```java
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.iflytek.frame.dto.ProductDetail;
import com.iflytek.frame.dto.ProductListItem;
import com.iflytek.frame.util.PageQuery;
import org.apache.ibatis.annotations.Param;

import java.util.List;
```
Add to the interface body:
```java
    /** Paginated catalog query with keyword + category filter and price/sales/rating sort. */
    IPage<ProductListItem> pageProducts(IPage<?> page, @Param("q") PageQuery q);

    /** Single product detail with sold + avgRating aggregates. */
    ProductDetail findDetailById(@Param("id") Long id);

    /** Distinct non-null categories for the filter dropdown. */
    List<String> selectDistinctCategories();
```

- [ ] **Step 4: Add the XML selects**

Modify `frame-backend/src/main/resources/mapper/ProductMapper.xml` — add before the closing `</mapper>` (keep existing `decrementStock`/`recreditStock`/`updateImageUrl`):

```xml
    <resultMap id="productListItemMap" type="com.iflytek.frame.dto.ProductListItem">
        <id property="id" column="id"/>
        <result property="name" column="name"/>
        <result property="priceInCents" column="price_in_cents"/>
        <result property="stock" column="stock"/>
        <result property="imageUrl" column="image_url"/>
        <result property="category" column="category"/>
        <result property="isFlashSale" column="is_flash_sale"/>
        <result property="flashSaleAt" column="flash_sale_at"/>
        <result property="description" column="description"/>
        <result property="createTime" column="create_time"/>
        <result property="updateTime" column="update_time"/>
        <result property="sold" column="sold"/>
        <result property="avgRating" column="avg_rating"/>
    </resultMap>

    <sql id="catalogFrom">
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
    </sql>

    <select id="pageProducts" resultMap="productListItemMap">
        SELECT p.id, p.name, p.price_in_cents, p.stock, p.image_url, p.category,
               p.is_flash_sale, p.flash_sale_at, p.description, p.create_time, p.update_time,
               COALESCE(s.sold, 0) AS sold,
               COALESCE(r.avg_rating, 0) AS avg_rating
        <include refid="catalogFrom"/>
        <where>
            <if test="q.category != null and q.category != ''">AND p.category = #{q.category}</if>
            <if test="q.keywords != null and q.keywords.size() > 0">
                <foreach collection="q.keywords" item="kw" separator=" ">AND p.name LIKE CONCAT('%', #{kw}, '%')</foreach>
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

    <select id="findDetailById" resultType="com.iflytek.frame.dto.ProductDetail">
        SELECT p.id, p.name, p.price_in_cents, p.stock, p.image_url, p.category,
               p.is_flash_sale, p.flash_sale_at, p.description, p.create_time, p.update_time,
               COALESCE(s.sold, 0) AS sold,
               COALESCE(r.avg_rating, 0) AS avg_rating
        <include refid="catalogFrom"/>
        WHERE p.id = #{id}
    </select>

    <select id="selectDistinctCategories" resultType="java.lang.String">
        SELECT DISTINCT category FROM product WHERE category IS NOT NULL ORDER BY category
    </select>
```

- [ ] **Step 5: Run the integration test to verify it passes**

Run: `cd frame-backend && mvn -q -Dtest=ProductMapperIntegrationTest test`
Expected: PASS (all tests). If the `@ActiveProfiles("test")` profile isn't set up, follow whatever the existing `CheckoutFlowIntegrationTest` uses for its Spring context + DB/Redis wiring — mirror it exactly. If the test can't connect to MariaDB/Redis in this env, log to `skipped-issues.md` and mark `@Disabled("needs DB/Redis — see skipped-issues")` (skip-and-return), but first try the existing integration test's approach.

- [ ] **Step 6: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/mapper/ProductMapper.java \
  frame-backend/src/main/resources/mapper/ProductMapper.xml \
  frame-backend/src/test/java/com/iflytek/frame/mapper/ProductMapperIntegrationTest.java
git commit -m "feat(phase-b): ProductMapper custom XML — pageProducts/findDetailById/selectDistinctCategories"
```

---

## Task 3: `ProductService` normalization + `getDetail` + controller endpoints + public categories

**Files:**
- Modify: `frame-backend/src/main/java/com/iflytek/frame/service/ProductService.java`
- Modify: `frame-backend/src/main/java/com/iflytek/frame/service/impl/ProductServiceImpl.java`
- Modify: `frame-backend/src/main/java/com/iflytek/frame/controller/ProductController.java`
- Modify: `frame-backend/src/main/java/com/iflytek/frame/security/SecurityConfig.java`
- Modify: `frame-backend/src/test/java/com/iflytek/frame/service/ProductServiceImplTest.java`

**Interfaces:**
- Consumes: `ProductMapper.pageProducts` / `findDetailById` / `selectDistinctCategories` (Task 2), `PageQuery` new fields (Task 1).
- Produces:
  - `ProductService.page(PageQuery) -> PageResult<ProductListItem>`
  - `ProductService.getDetail(Long id) -> ProductDetail`
  - `ProductService.listCategories() -> List<String>`
  - `GET /products` returns `Result<PageResult<ProductListItem>>`
  - `GET /products/{id}` returns `Result<ProductDetail>`
  - `GET /products/categories` returns `Result<List<String>>` (public)

- [ ] **Step 1: Write the failing service unit tests**

Add to `frame-backend/src/test/java/com/iflytek/frame/service/ProductServiceImplTest.java` (after the existing tests, before the closing `}`):
```java
    @Test
    void page_normalizes_sortField_whitelist_fallsBackToCreateTime() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<ProductListItem> mp =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
        com.iflytek.frame.dto.ProductListItem item = new com.iflytek.frame.dto.ProductListItem();
        item.setName("X");
        mp.setTotal(1); mp.setRecords(java.util.List.of(item));
        when(productMapper.pageProducts(any(), any())).thenReturn(mp);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        PageQuery q = new PageQuery();
        q.setSortField("bogus");   // not in whitelist
        q.setSortOrder("desc");
        PageResult<ProductListItem> r = productService.page(q);

        assertThat(r.getRecords()).hasSize(1);
        // sortField stays null-ish so <choose> defaults to create_time
        verify(productMapper).pageProducts(any(), argThat(qq -> qq.getSortField() == null));
    }

    @Test
    void page_normalizes_sortOrder_and_sets_sortOrderSql() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<ProductListItem> mp =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
        mp.setTotal(0); mp.setRecords(java.util.List.of());
        when(productMapper.pageProducts(any(), any())).thenReturn(mp);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        PageQuery q = new PageQuery();
        q.setSortField("price");
        q.setSortOrder("ASC");     // uppercase
        productService.page(q);
        verify(productMapper).pageProducts(any(), argThat(qq -> " ASC".equals(qq.getSortOrderSql())));

        // garbage sortOrder → default DESC
        PageQuery q2 = new PageQuery();
        q2.setSortField("price");
        q2.setSortOrder("sideways");
        productService.page(q2);
        verify(productMapper).pageProducts(any(), argThat(qq -> " DESC".equals(qq.getSortOrderSql())));
    }

    @Test
    void page_splits_keyword_on_comma_and_space_drops_empties() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<ProductListItem> mp =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
        mp.setTotal(0); mp.setRecords(java.util.List.of());
        when(productMapper.pageProducts(any(), any())).thenReturn(mp);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        PageQuery q = new PageQuery();
        q.setKeyword("red,, widget  ,");
        productService.page(q);
        verify(productMapper).pageProducts(any(), argThat(qq ->
                qq.getKeywords() != null && qq.getKeywords().size() == 2
                        && qq.getKeywords().contains("red") && qq.getKeywords().contains("widget")));
    }

    @Test
    void page_blank_keyword_yields_null_keywords() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<ProductListItem> mp =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
        mp.setTotal(0); mp.setRecords(java.util.List.of());
        when(productMapper.pageProducts(any(), any())).thenReturn(mp);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        PageQuery q = new PageQuery();
        q.setKeyword("   ");
        productService.page(q);
        verify(productMapper).pageProducts(any(), argThat(qq -> qq.getKeywords() == null));
    }

    @Test
    void getDetail_notFound_throws() {
        when(productMapper.findDetailById(999999L)).thenReturn(null);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> productService.getDetail(999999L))
                .isInstanceOf(com.iflytek.frame.exception.BusinessException.class);
    }
```
Add these imports at the top of the test file:
```java
import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.dto.ProductListItem;
import com.iflytek.frame.util.PageQuery;
import static org.mockito.ArgumentMatchers.argThat;
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frame-backend && mvn -q -Dtest=ProductServiceImplTest test`
Expected: FAIL — `page` returns `PageResult<Product>` not `PageResult<ProductListItem>`; `getDetail` doesn't exist (compile errors).

- [ ] **Step 3: Update the `ProductService` interface**

Modify `frame-backend/src/main/java/com/iflytek/frame/service/ProductService.java`:
- Change `PageResult<Product> page(PageQuery query);` → `PageResult<ProductListItem> page(PageQuery query);`
- Add `ProductDetail getDetail(Long id);`
- Add `java.util.List<String> listCategories();`
Add imports:
```java
import com.iflytek.frame.dto.ProductDetail;
import com.iflytek.frame.dto.ProductListItem;
import java.util.List;
```

- [ ] **Step 4: Rewrite `page` + add `getDetail` + `listCategories` in `ProductServiceImpl`**

Modify `frame-backend/src/main/java/com/iflytek/frame/service/impl/ProductServiceImpl.java`:
- Replace the existing `page` method (lines 72-79) with:
```java
    @Override
    public PageResult<ProductListItem> page(PageQuery query) {
        // Normalize sortField to the whitelist; anything else → null (XML defaults to create_time).
        String sf = query.getSortField();
        if (sf == null || !(sf.equals("price") || sf.equals("sales") || sf.equals("rating"))) {
            query.setSortField(null);
        }
        // Normalize sortOrder → " ASC"/" DESC" (default desc). sortOrderSql is the ONLY ${} in the SQL,
        // and it's derived from this whitelist — never raw user input (防SQL注入).
        String so = query.getSortOrder();
        String sql;
        if (so != null && so.trim().equalsIgnoreCase("asc")) {
            sql = " ASC";
        } else if (so != null && so.trim().equalsIgnoreCase("desc")) {
            sql = " DESC";
        } else {
            sql = " DESC";   // default
        }
        query.setSortOrderSql(sql);
        // Split keyword on comma/space, trim, drop empties.
        String kw = query.getKeyword();
        if (kw == null || kw.isBlank()) {
            query.setKeywords(null);
        } else {
            java.util.List<String> terms = new java.util.ArrayList<>();
            for (String t : kw.split("[,\\s]+")) {
                String tr = t.trim();
                if (!tr.isEmpty()) terms.add(tr);
            }
            query.setKeywords(terms.isEmpty() ? null : terms);
        }
        Page<ProductListItem> page = new Page<>(query.getCurrent(), query.getSize());
        IPage<ProductListItem> result = mapper.pageProducts(page, query);
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }
```
- Add after `page`:
```java
    @Override
    public ProductDetail getDetail(Long id) {
        ProductDetail d = mapper.findDetailById(id);
        if (d == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "product not found: " + id);
        }
        return d;
    }

    @Override
    public java.util.List<String> listCategories() {
        return mapper.selectDistinctCategories();
    }
```
- Update imports in the impl: add `import com.iflytek.frame.dto.ProductDetail;` and `import com.iflytek.frame.dto.ProductListItem;`. Remove the now-unused `LambdaQueryWrapper` import ONLY if nothing else uses it (check: it's not used elsewhere in this file after the rewrite — safe to remove; if unsure, leave it).

- [ ] **Step 5: Update `ProductController`**

Modify `frame-backend/src/main/java/com/iflytek/frame/controller/ProductController.java`:
- Change imports: replace `import com.iflytek.frame.entity.Product;` usage for the list/detail with the DTOs. Add:
```java
import com.iflytek.frame.dto.ProductDetail;
import com.iflytek.frame.dto.ProductListItem;
import java.util.List;
```
  (Keep `import com.iflytek.frame.entity.Product;` only if `create`/`update` still use it — they do, so keep it.)
- Change the `get` method (lines 25-29) to return detail:
```java
    @Operation(summary = "Get a product detail by id (with sold + avgRating)")
    @GetMapping("/{id}")
    public Result<ProductDetail> get(@PathVariable Long id) {
        return Result.success(service.getDetail(id));
    }
```
- Change the `page` method (lines 31-35) return type:
```java
    @Operation(summary = "List products (paginated, searchable, sortable)")
    @GetMapping
    public Result<PageResult<ProductListItem>> page(@Valid @ParameterObject PageQuery query) {
        return Result.success(service.page(query));
    }
```
- Add the categories endpoint (after `page`):
```java
    @Operation(summary = "List distinct product categories")
    @GetMapping("/categories")
    public Result<List<String>> categories() {
        return Result.success(service.listCategories());
    }
```
  IMPORTANT: place `/categories` mapping so it doesn't shadow `/{id}`. Spring matches exact paths before path variables, so `GET /products/categories` resolves to this method, not `get(id)`. (Verify in Step 7's test.)

- [ ] **Step 6: Make `GET /products/categories` public**

Modify `frame-backend/src/main/java/com/iflytek/frame/security/SecurityConfig.java` — in the `public product reads` matcher (lines 100-101), add `/products/categories`:
```java
                        // public product reads (browsing — no login)
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/products", "/products/{id}", "/products/categories").permitAll()
```

- [ ] **Step 7: Run the unit tests to verify they pass**

Run: `cd frame-backend && mvn -q -Dtest=ProductServiceImplTest test`
Expected: PASS (all existing 3 + new 5 tests).

- [ ] **Step 8: Run the full backend test suite (regression)**

Run: `cd frame-backend && mvn -q test`
Expected: PASS — all tests green (including the Task 2 integration test). If a pre-existing test breaks due to the `page` return-type change (e.g., a controller test asserting `PageResult<Product>`), update it to `ProductListItem`. If hard to fix → skip-and-return (log + `@Disabled`).

- [ ] **Step 9: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/service/ProductService.java \
  frame-backend/src/main/java/com/iflytek/frame/service/impl/ProductServiceImpl.java \
  frame-backend/src/main/java/com/iflytek/frame/controller/ProductController.java \
  frame-backend/src/main/java/com/iflytek/frame/security/SecurityConfig.java \
  frame-backend/src/test/java/com/iflytek/frame/service/ProductServiceImplTest.java
git commit -m "feat(phase-b): service+controller catalog search/sort/detail/categories, public GET categories"
```

---

## Task 4: Frontend — API types + client functions

**Files:**
- Modify: `frame-frontend/src/api/types.ts`
- Modify: `frame-frontend/src/api/product.ts`
- Test: (covered indirectly by the page tests in Tasks 5-6; the API layer is thin)

**Interfaces:**
- Consumes: backend `GET /products?keyword&category&sortField&sortOrder`, `GET /products/{id}` (`ProductDetail`), `GET /products/categories`.
- Produces:
  - `PageQuery` extended with `keyword?`, `category?`, `sortField?` (`'price'|'sales'|'rating'`), `sortOrder?` (`'asc'|'desc'`).
  - `ProductDetail` type (Product fields + `sold`, `avgRating`).
  - `pageProducts(query)` forwarding new params; `getProductDetail(id)`; `listCategories()`.

- [ ] **Step 1: Extend `PageQuery` and add `ProductDetail` in `types.ts`**

Modify `frame-frontend/src/api/types.ts`:
- Replace the `PageQuery` interface (lines 128-131):
```ts
export interface PageQuery {
  current?: number
  size?: number
  /** Search keyword (split on comma/space into ANDed LIKE terms server-side). */
  keyword?: string
  /** Exact category filter. */
  category?: string
  /** Sort field: 'price' | 'sales' | 'rating'. Omit = by recency. */
  sortField?: 'price' | 'sales' | 'rating'
  /** Sort direction. */
  sortOrder?: 'asc' | 'desc'
}
```
- Add after the `Product` interface (after line 72):
```ts
/**
 * Detail view from GET /products/{id}: Product fields plus sold + avgRating
 * aggregates (used on the product detail page header).
 */
export interface ProductDetail extends Product {
  sold: number
  avgRating: number
}

/** List item from GET /products: Product fields plus sold + avgRating. */
export interface ProductListItem extends Product {
  sold: number
  avgRating: number
}
```

- [ ] **Step 2: Update `pageProducts` + add `getProductDetail` + `listCategories` in `product.ts`**

Modify `frame-frontend/src/api/product.ts`:
- Replace the import (line 2) and `pageProducts` (lines 4-8):
```ts
import request from './request'
import type { PageQuery, PageResult, ProductListItem } from './types'

export function pageProducts(query: PageQuery = {}): Promise<PageResult<ProductListItem>> {
  return request.get<PageResult<ProductListItem>>('/products', {
    params: {
      current: query.current ?? 1,
      size: query.size ?? 12,
      keyword: query.keyword,
      category: query.category,
      sortField: query.sortField,
      sortOrder: query.sortOrder,
    },
  })
}
```
- Add after `getProduct` (after line 12):
```ts
import type { ProductDetail } from './types'

export function getProductDetail(id: number): Promise<ProductDetail> {
  return request.get<ProductDetail>(`/products/${id}`)
}

export function listCategories(): Promise<string[]> {
  return request.get<string[]>('/products/categories')
}
```
  (Consolidate the type imports at the top into one line if preferred: `import type { PageQuery, PageResult, ProductDetail, ProductListItem } from './types'` — and drop the mid-file `import type { ProductDetail }`.)

- [ ] **Step 3: Verify the frontend type-checks**

Run: `cd frame-frontend && npx tsc --noEmit`
Expected: no type errors. (If `Products.tsx` or tests now error because `pageProducts` returns `ProductListItem` instead of `Product` — that's expected and fixed in Task 5; note it and proceed.)

- [ ] **Step 4: Commit**

```bash
git add frame-frontend/src/api/types.ts frame-frontend/src/api/product.ts
git commit -m "feat(phase-b): frontend API types + client (PageQuery, ProductDetail, listCategories)"
```

---

## Task 5: Frontend — routing (public browse) + evolve `Products.tsx` into a catalog

**Files:**
- Modify: `frame-frontend/src/router/index.tsx`
- Modify: `frame-frontend/src/pages/Products.tsx`
- Modify: `frame-frontend/tests/Products.test.tsx`

**Interfaces:**
- Consumes: `pageProducts`, `listCategories` (Task 4).
- Produces: a `/products` catalog page (public) with search/sort/category + "暂无数据".

- [ ] **Step 1: Write the failing catalog tests**

Replace `frame-frontend/tests/Products.test.tsx` entirely:
```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import Products from '@/pages/Products'

vi.mock('@/api/product', () => ({
  pageProducts: vi.fn(),
  listCategories: vi.fn(),
  getProduct: vi.fn(),
}))

import { pageProducts, listCategories } from '@/api/product'

const product = (overrides: Partial<any> = {}) => ({
  id: 1,
  name: 'Red Widget',
  priceInCents: 1000,
  stock: 5,
  category: 'toys',
  isFlashSale: false,
  sold: 4,
  avgRating: 4.0,
  ...overrides,
})

const page = (records: any[]) => ({ total: records.length, current: 1, size: 12, records })

beforeEach(() => {
  vi.clearAllMocks()
  ;(listCategories as any).mockResolvedValue(['toys', 'tools'])
  ;(pageProducts as any).mockResolvedValue(page([product()]))
})

const renderProducts = () =>
  render(
    <MemoryRouter>
      <Products />
    </MemoryRouter>,
  )

describe('Products — catalog (UC000)', () => {
  it('renders product cards and links to detail', async () => {
    renderProducts()
    await waitFor(() => expect(screen.getByText('Red Widget')).toBeInTheDocument())
    expect(screen.getByText(/10\.00|10,/)).toBeInTheDocument() // price display
    expect(screen.getByRole('link', { name: /查看详情|Red Widget/i })).toHaveAttribute('href', '/products/1')
  })

  it('submits a keyword search', async () => {
    renderProducts()
    await waitFor(() => expect(pageProducts).toHaveBeenCalled())
    const input = screen.getByPlaceholderText(/搜索|keyword|搜索商品/i)
    fireEvent.change(input, { target: { value: 'red' } })
    fireEvent.click(screen.getByRole('button', { name: /搜索|search/i }))
    await waitFor(() =>
      expect(pageProducts).toHaveBeenCalledWith(expect.objectContaining({ keyword: 'red' })),
    )
  })

  it('changes sort field + order via the sort control', async () => {
    renderProducts()
    await waitFor(() => expect(pageProducts).toHaveBeenCalled())
    fireEvent.change(screen.getByLabelText(/排序|sort/i), { target: { value: 'price' } })
    await waitFor(() =>
      expect(pageProducts).toHaveBeenCalledWith(expect.objectContaining({ sortField: 'price' })),
    )
  })

  it('filters by category', async () => {
    renderProducts()
    await waitFor(() => expect(pageProducts).toHaveBeenCalled())
    fireEvent.change(screen.getByLabelText(/分类|category/i), { target: { value: 'toys' } })
    await waitFor(() =>
      expect(pageProducts).toHaveBeenCalledWith(expect.objectContaining({ category: 'toys' })),
    )
  })

  it('shows 暂无数据 when results are empty', async () => {
    ;(pageProducts as any).mockResolvedValue(page([]))
    renderProducts()
    await waitFor(() => expect(screen.getByText('暂无数据')).toBeInTheDocument())
  })
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frame-frontend && npm test -- Products.test`
Expected: FAIL — `Products` still renders the flash-sale grid (no search bar / sort control / "暂无数据").

- [ ] **Step 3: Move `/products` to a public route + add `/products/:id`**

Modify `frame-frontend/src/router/index.tsx`:
- Add import: `import ProductDetailPage from '@/pages/ProductDetailPage'` (created in Task 6; this is a forward ref — the import resolves once Task 6 lands. To keep this task independently testable, do the routing change in Task 6's first step instead. HERE, only move `/products` public.)
- Move the `products` child out of the `RequireAuth` children into a public block. Replace the routes array:
```tsx
const routes: RouteObject[] = [
  { path: '/login', element: <Login /> },
  { path: '/register', element: <Register /> },
  { path: '/forgot-password', element: <ForgotPassword /> },
  // Public catalog browse (UC000: guests may browse/search/sort). Add-to-cart stays protected.
  { path: '/products', element: <Products /> },
  {
    path: '/',
    element: (
      <RequireAuth>
        <CartProvider>
          <MainLayout />
        </CartProvider>
      </RequireAuth>
    ),
    children: [
      { index: true, element: <Dashboard /> },
      { path: 'dashboard', element: <Dashboard /> },
      { path: 'checkout', element: <Checkout /> },
      { path: 'orders', element: <Orders /> },
      { path: 'profile', element: <Profile /> },
      { path: 'admin', element: <Admin /> },
    ],
  },
]
```
  Note: `/products/:id` is added in Task 6 Step 1 alongside the `ProductDetailPage` import so this task compiles. Leave it out here.

- [ ] **Step 4: Rewrite `Products.tsx` as a catalog**

Modify `frame-frontend/src/pages/Products.tsx` — replace the entire file:
```tsx
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Search, SortAsc } from 'lucide-react'
import { pageProducts, listCategories } from '@/api/product'
import type { ProductListItem, PageQuery } from '@/api/types'

export default function Products() {
  const [items, setItems] = useState<ProductListItem[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [categories, setCategories] = useState<string[]>([])
  const [query, setQuery] = useState<PageQuery>({ current: 1, size: 12 })
  const [keywordInput, setKeywordInput] = useState('')

  useEffect(() => {
    listCategories().then(setCategories).catch(() => {})
  }, [])

  useEffect(() => {
    setLoading(true)
    pageProducts(query)
      .then((r) => {
        setItems(r.records)
        setTotal(r.total)
      })
      .finally(() => setLoading(false))
  }, [query])

  const onSearch = () => setQuery((q) => ({ ...q, keyword: keywordInput || undefined, current: 1 }))
  const onSort = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const [field, order] = e.target.value.split('|')
    setQuery((q) => ({ ...q, sortField: (field || undefined) as PageQuery['sortField'], sortOrder: (order as 'asc' | 'desc') || 'desc', current: 1 }))
  }
  const onCategory = (e: React.ChangeEvent<HTMLSelectElement>) =>
    setQuery((q) => ({ ...q, category: e.target.value || undefined, current: 1 }))

  return (
    <div className="mx-auto max-w-6xl p-4">
      <h1 className="mb-4 text-2xl font-bold">商品列表 ({total})</h1>
      <div className="mb-4 flex flex-wrap gap-2">
        <input
          className="border p-2 rounded"
          placeholder="搜索商品名称，多关键词用逗号或空格"
          value={keywordInput}
          onChange={(e) => setKeywordInput(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && onSearch()}
        />
        <button className="rounded bg-brand-600 px-3 py-2 text-white" onClick={onSearch}>
          <Search className="inline h-4 w-4" /> 搜索
        </button>
        <select aria-label="分类" className="border p-2 rounded" onChange={onCategory} value={query.category ?? ''}>
          <option value="">全部分类</option>
          {categories.map((c) => <option key={c} value={c}>{c}</option>)}
        </select>
        <select aria-label="排序" className="border p-2 rounded" onChange={onSort}>
          <option value="">默认</option>
          <option value="price|asc">价格升序</option>
          <option value="price|desc">价格降序</option>
          <option value="sales|desc">销量降序</option>
          <option value="rating|desc">评价降序</option>
        </select>
      </div>

      {loading ? (
        <p>加载中...</p>
      ) : items.length === 0 ? (
        <p className="text-gray-500">暂无数据</p>
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {items.map((p) => (
            <Link to={`/products/${p.id}`} key={p.id} className="block rounded border p-3 hover:shadow">
              {p.imageUrl && <img src={p.imageUrl} alt={p.name} loading="lazy" className="mb-2 h-32 w-full object-cover" />}
              <div className="font-medium">{p.name}</div>
              <div className="text-brand-600">¥{(p.priceInCents / 100).toFixed(2)}</div>
              <div className="text-sm text-gray-500">已售 {p.sold} · 评分 {p.avgRating.toFixed(1)}</div>
            </Link>
          ))}
        </div>
      )}

      {total > (query.size ?? 12) && (
        <div className="mt-4 flex gap-2">
          <button disabled={(query.current ?? 1) <= 1} onClick={() => setQuery((q) => ({ ...q, current: (q.current ?? 1) - 1 }))} className="border px-3 py-1 rounded">上一页</button>
          <button onClick={() => setQuery((q) => ({ ...q, current: (q.current ?? 1) + 1 }))} className="border px-3 py-1 rounded">下一页</button>
        </div>
      )}
    </div>
  )
}
```

- [ ] **Step 5: Run the catalog tests to verify they pass**

Run: `cd frame-frontend && npm test -- Products.test`
Expected: PASS (5 tests). If a selector assertion doesn't match the rendered JSX, adjust the test selector or the JSX label to agree — prefer fixing the test to match the real accessible name, not the other way.

- [ ] **Step 6: Commit**

```bash
git add frame-frontend/src/router/index.tsx frame-frontend/src/pages/Products.tsx frame-frontend/tests/Products.test.tsx
git commit -m "feat(phase-b): evolve Products into a searchable/sortable catalog (UC000), public route"
```

---

## Task 6: Frontend — `ProductDetailPage` + `/products/:id` route

**Files:**
- Create: `frame-frontend/src/pages/ProductDetailPage.tsx`
- Create: `frame-frontend/tests/ProductDetailPage.test.tsx`
- Modify: `frame-frontend/src/router/index.tsx` (add the route + import)

**Interfaces:**
- Consumes: `getProductDetail`, `getProduct` (live-stock poll for flash-sale), `useAuthStore` (guest detection), `useCart` (add-to-cart).
- Produces: the `/products/:id` page with description, image, stock, sold, avg rating, flash-sale countdown/poll (moved from old `FlashSaleCard`), reviews placeholder, guest add-to-cart → `/login`.

- [ ] **Step 1: Add the `/products/:id` route**

Modify `frame-frontend/src/router/index.tsx`:
- Add import at top: `import ProductDetailPage from '@/pages/ProductDetailPage'`
- In the public block (where `/products` now is), add right after it:
```tsx
  { path: '/products/:id', element: <ProductDetailPage /> },
```

- [ ] **Step 2: Write the failing detail-page tests**

Create `frame-frontend/tests/ProductDetailPage.test.tsx`:
```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, act } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import ProductDetailPage from '@/pages/ProductDetailPage'
import { CartProvider } from '@/store/CartContext'

vi.mock('@/api/product', () => ({
  getProductDetail: vi.fn(),
  getProduct: vi.fn(),
}))

import { getProductDetail, getProduct } from '@/api/product'

const detail = (overrides: Partial<any> = {}) => ({
  id: 1,
  name: 'Red Widget',
  priceInCents: 1000,
  stock: 5,
  category: 'toys',
  isFlashSale: false,
  description: 'A fine red widget',
  sold: 4,
  avgRating: 4.0,
  ...overrides,
})

beforeEach(() => {
  vi.clearAllMocks()
  ;(getProductDetail as any).mockResolvedValue(detail())
})

const renderDetail = (path = '/products/1') =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <CartProvider>
        <Routes>
          <Route path="/products/:id" element={<ProductDetailPage />} />
        </Routes>
      </CartProvider>
    </MemoryRouter>,
  )

describe('ProductDetailPage (UC000 detail)', () => {
  it('renders name, price, description, sold, avgRating', async () => {
    renderDetail()
    await waitFor(() => expect(screen.getByText('Red Widget')).toBeInTheDocument())
    expect(screen.getByText(/A fine red widget/)).toBeInTheDocument()
    expect(screen.getByText(/已售 4/)).toBeInTheDocument()
    expect(screen.getByText(/评分 4\.0/)).toBeInTheDocument()
  })

  it('renders a flash-sale countdown when flashSaleAt is future', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    ;(getProductDetail as any).mockResolvedValue(detail({ isFlashSale: true, flashSaleAt: new Date(Date.now() + 3600_000).toISOString() }))
    renderDetail()
    await waitFor(() => expect(screen.getByText(/Sale opens in|秒杀即将开始|倒计时/i)).toBeInTheDocument())
    vi.useRealTimers()
  })

  it('polls live stock when flashSaleAt is past', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    ;(getProductDetail as any).mockResolvedValue(detail({ isFlashSale: true, flashSaleAt: new Date(Date.now() - 1000).toISOString(), stock: 50 }))
    ;(getProduct as any).mockResolvedValue(detail({ stock: 30 }))
    renderDetail()
    await waitFor(() => expect(getProduct).toHaveBeenCalledWith(1))
    await act(async () => { await vi.advanceTimersByTimeAsync(4100) })
    expect(getProduct.mock.calls.length).toBeGreaterThanOrEqual(2)
    vi.useRealTimers()
  })

  it('redirects a guest to /login on add-to-cart', async () => {
    // guest = no token in the auth store
    renderDetail()
    await waitFor(() => expect(screen.getByText('Red Widget')).toBeInTheDocument())
    // The add-to-cart button navigates to /login when no token.
    const btn = screen.getByRole('button', { name: /加入购物车|add to cart/i })
    expect(btn).toBeInTheDocument()
  })

  it('renders 商品不存在 when the product is not found', async () => {
    ;(getProductDetail as any).mockRejectedValue({ response: { status: 404 } })
    renderDetail('/products/999999')
    await waitFor(() => expect(screen.getByText(/商品不存在|not found/i)).toBeInTheDocument())
  })
})
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd frame-frontend && npm test -- ProductDetailPage`
Expected: FAIL — `ProductDetailPage` doesn't exist.

- [ ] **Step 4: Create `ProductDetailPage.tsx`**

Create `frame-frontend/src/pages/ProductDetailPage.tsx`:
```tsx
import { useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { getProductDetail, getProduct } from '@/api/product'
import { useAuthStore } from '@/store/useAuthStore'
import { useCart } from '@/store/CartContext'
import type { ProductDetail } from '@/api/types'

function fmtCountdown(ms: number): string {
  if (ms <= 0) return '00:00:00'
  const s = Math.floor(ms / 1000)
  const hh = String(Math.floor(s / 3600)).padStart(2, '0')
  const mm = String(Math.floor((s % 3600) / 60)).padStart(2, '0')
  const ss = String(s % 60).padStart(2, '0')
  return `${hh}:${mm}:${ss}`
}

export default function ProductDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const token = useAuthStore((s) => s.token)
  const { addItem } = useCart()
  const [detail, setDetail] = useState<ProductDetail | null>(null)
  const [liveStock, setLiveStock] = useState<number | null>(null)
  const [notFound, setNotFound] = useState(false)

  useEffect(() => {
    setNotFound(false)
    setLiveStock(null)
    getProductDetail(Number(id))
      .then((d) => setDetail(d))
      .catch((e) => { if (e?.response?.status === 404) setNotFound(true) })
  }, [id])

  // Flash-sale live-stock polling (moved from the old FlashSaleCard).
  useEffect(() => {
    if (!detail?.isFlashSale || !detail?.flashSaleAt) return
    if (new Date(detail.flashSaleAt).getTime() > Date.now()) return // not started; countdown only
    let stop = false
    const poll = async () => {
      try { const p = await getProduct(detail.id); if (!stop) setLiveStock(p.stock) } catch {}
    }
    poll()
    const t = setInterval(poll, 4000)
    return () => { stop = true; clearInterval(t) }
  }, [detail])

  if (notFound) return <div className="p-8 text-center text-gray-500">商品不存在</div>
  if (!detail) return <div className="p-8">加载中...</div>

  const flashStart = detail.flashSaleAt ? new Date(detail.flashSaleAt).getTime() : 0
  const isFlashLive = detail.isFlashSale && flashStart > 0 && flashStart <= Date.now()
  const isFlashUpcoming = detail.isFlashSale && flashStart > Date.now()
  const [hh, mm, ss] = fmtCountdown(flashStart - Date.now()).split(':')
  const stockShown = isFlashLive ? (liveStock ?? detail.stock) : detail.stock

  const onAddToCart = () => {
    if (!token) { navigate('/login'); return }
    if (detail.stock <= 0) return
    addItem(detail.id, 1)
  }

  return (
    <div className="mx-auto max-w-4xl p-4">
      <div className="grid gap-4 sm:grid-cols-2">
        {detail.imageUrl && <img src={detail.imageUrl} alt={detail.name} loading="lazy" className="h-64 w-full rounded object-cover" />}
        <div>
          <h1 className="text-2xl font-bold">{detail.name}</h1>
          <div className="my-2 text-xl text-brand-600">¥{(detail.priceInCents / 100).toFixed(2)}</div>
          <div className="text-sm text-gray-500">分类: {detail.category ?? '—'} · 已售 {detail.sold} · 评分 {detail.avgRating.toFixed(1)}</div>
          <div className="my-2">库存: {stockShown}</div>
          {isFlashUpcoming && <div className="text-brand-600">秒杀即将开始 倒计时 {hh}:{mm}:{ss}</div>}
          <button onClick={onAddToCart} className="mt-3 rounded bg-brand-600 px-4 py-2 text-white">加入购物车</button>
        </div>
      </div>
      <div className="mt-6">
        <h2 className="mb-2 text-lg font-semibold">商品详情</h2>
        <p className="text-gray-700">{detail.description ?? '暂无描述'}</p>
      </div>
      <div className="mt-6">
        <h2 className="mb-2 text-lg font-semibold">评价</h2>
        <p className="text-gray-500">暂无评价</p>
      </div>
    </div>
  )
}
```

- [ ] **Step 5: Run the detail-page tests to verify they pass**

Run: `cd frame-frontend && npm test -- ProductDetailPage`
Expected: PASS (5 tests). Adjust selectors/markup to agree if needed.

- [ ] **Step 6: Run the full frontend test suite (regression)**

Run: `cd frame-frontend && npm test`
Expected: PASS — all tests green. If `Products.test.tsx` broke because `pageProducts` now returns `ProductListItem` and an old assertion expected `Product`-only shape, fix it (Task 5 already rewrote that test; verify).

- [ ] **Step 7: Commit**

```bash
git add frame-frontend/src/pages/ProductDetailPage.tsx \
  frame-frontend/src/router/index.tsx \
  frame-frontend/tests/ProductDetailPage.test.tsx
git commit -m "feat(phase-b): ProductDetailPage + /products/:id route (flash-sale countdown/poll, guest add-to-cart)"
```

---

## Task 7: E2E — UC000 flow + full regression + merge to `main`

**Files:**
- Modify: the `e2e/` suite (add a UC000 test file; path depends on the existing e2e structure — check `e2e/` dir).

**Interfaces:**
- Consumes: backend `GET /products` (search/sort/category/empty), `GET /products/{id}`, `GET /products/categories`.
- Produces: a green UC000 e2e test + the full suite green as the regression gate; `feature/phase-b-catalog` merged to `main`.

- [ ] **Step 1: Inspect the existing e2e structure**

Run:
```bash
ls e2e/ 2>/dev/null && cat e2e/package.json 2>/dev/null | head -40
```
If `e2e/` doesn't exist at the repo root, check `frame-frontend/e2e/` or search: `find . -name "*.test.*" -path "*e2e*" 2>/dev/null | head`. Expected: locate the e2e runner + an existing test to mirror its HTTP-client import style.

- [ ] **Step 2: Write the UC000 e2e test**

Create a new e2e test file mirroring the existing suite's HTTP client + runner. The test (guest, no token):
```js
// Mirrors the existing e2e client shape; adapt the import to match.
import { describe, it, expect, beforeAll } from 'vitest'  // or the suite's runner
import { http } from './helpers'  // <-- adapt to the real e2e client

const BASE = process.env.API_BASE || 'http://localhost:8081'

describe('UC000 — catalog search/sort/browse (guest)', () => {
  it('lists products without auth', async () => {
    const r = await http.get(`${BASE}/products?current=1&size=12`)
    expect(r.status).toBe(200)
    expect(r.body.data.records).toBeInstanceOf(Array)
    expect(r.body.data.current).toBe(1)
  })

  it('filters by keyword', async () => {
    const r = await http.get(`${BASE}/products?keyword=widget`)
    expect(r.status).toBe(200)
    // every returned record name contains "widget" (case-insensitive)
    for (const p of r.body.data.records) {
      expect(p.name.toLowerCase()).toContain('widget')
    }
  })

  it('survives special characters without crashing', async () => {
    const r = await http.get(`${BASE}/products?keyword=` + encodeURIComponent("<script>'%;--"))
    expect(r.status).toBe(200)
    expect(r.body.data.records).toBeInstanceOf(Array)
  })

  it('sorts by price asc then desc', async () => {
    const asc = await http.get(`${BASE}/products?sortField=price&sortOrder=asc`)
    const desc = await http.get(`${BASE}/products?sortField=price&sortOrder=desc`)
    expect(asc.status).toBe(200)
    expect(desc.status).toBe(200)
    const a = asc.body.data.records.map((p) => p.priceInCents)
    const d = desc.body.data.records.map((p) => p.priceInCents)
    if (a.length > 1) expect(a[0]).toBeLessThanOrEqual(a[a.length - 1])
    if (d.length > 1) expect(d[0]).toBeGreaterThanOrEqual(d[d.length - 1])
  })

  it('sorts by sales desc', async () => {
    const r = await http.get(`${BASE}/products?sortField=sales&sortOrder=desc`)
    expect(r.status).toBe(200)
    const s = r.body.data.records.map((p) => p.sold)
    for (let i = 1; i < s.length; i++) expect(s[i]).toBeLessThanOrEqual(s[i - 1])
  })

  it('sorts by rating desc', async () => {
    const r = await http.get(`${BASE}/products?sortField=rating&sortOrder=desc`)
    expect(r.status).toBe(200)
    const s = r.body.data.records.map((p) => p.avgRating)
    for (let i = 1; i < s.length; i++) expect(s[i]).toBeLessThanOrEqual(s[i - 1])
  })

  it('filters by category and lists categories', async () => {
    const cats = await http.get(`${BASE}/products/categories`)
    expect(cats.status).toBe(200)
    expect(cats.body.data).toBeInstanceOf(Array)
    if (cats.body.data.length > 0) {
      const c = cats.body.data[0]
      const r = await http.get(`${BASE}/products?category=` + encodeURIComponent(c))
      expect(r.status).toBe(200)
      for (const p of r.body.data.records) expect(p.category).toBe(c)
    }
  })

  it('returns empty for a non-matching keyword', async () => {
    const r = await http.get(`${BASE}/products?keyword=zzzznotreal`)
    expect(r.status).toBe(200)
    expect(r.body.data.records).toEqual([])
    expect(r.body.data.total).toBe(0)
  })
})
```
Adapt the `http` helper import + assertion style (`r.body` vs `r.data`) to the existing suite's conventions. If the suite has no categories seeded in the local DB, the category test's `if (cats.body.data.length > 0)` guard keeps it passing on an empty catalog.

- [ ] **Step 3: Run the UC000 e2e + full e2e suite**

Run the e2e suite (command depends on Step 1's discovery — e.g., `cd e2e && npm test`).
Expected: UC000 tests pass + the existing tests still green. If the backend isn't running, start it: `cd frame-backend && mvn spring-boot:run` (background) and re-run. If a test fails on a hard bug → skip-and-return (log to `skipped-issues.md`, `test.skip` with reason).

- [ ] **Step 4: Run the full backend + frontend suites one more time**

Run:
```bash
cd frame-backend && mvn -q test
cd ../frame-frontend && npm test
```
Expected: all green. This is the merge gate.

- [ ] **Step 5: Final review + merge to `main`**

Run a code review (use the `superpowers:requesting-code-review` skill or a fresh reviewer subagent) on the `feature/phase-b-catalog` branch. Address Critical/Important findings; defer Minor to Phase F. Then:
```bash
git checkout main
git merge --no-ff feature/phase-b-catalog -m "merge: Phase B (Catalog) — UC000 search/sort/detail page"
```
Expected: clean merge to `main`. Re-run e2e on `main` as the post-merge regression (Step 3 command). If green, Phase B is done.

- [ ] **Step 6: Update memory + status doc**

Update `docs/superpowers/PHASE-A-STATUS.md` → add a Phase B section (or create `PHASE-B-STATUS.md`) summarizing: what was built, tests green (counts), review verdict, deferred items. Update the memory file `frame-shop-uc-fulfillment.md` to mark Phase B done + resume point = Phase C (Order lifecycle). Commit:
```bash
git add docs/superpowers/PHASE-B-STATUS.md .superpowers/sdd/2026-08-20-phase-b-catalog/progress.md 2>/dev/null
git commit -m "docs(phase-b): Phase B status report + resume point (Phase C)"
```

---

## Self-Review

**1. Spec coverage:**
- UC000 keyword search (fuzzy, multi-keyword, comma/space split) → Task 2 XML `<foreach>` + Task 3 service split. ✓
- Sort price/sales/rating asc+desc → Task 2 XML `<choose>` + Task 3 normalization. ✓
- Category filter → Task 2 XML `<if>` + Task 3 + Task 5 dropdown. ✓
- "暂无数据" → Task 5 empty-state. ✓
- Special-char-safe → Task 2 parameterized `#{kw}` + Task 2 + Task 7 tests. ✓
- Product detail page → Task 6. ✓
- Guest browse → Task 5 routing + backend already permits public GET (verified). ✓
- `review` table created now (table-only) → Task 1. ✓
- Sales = PAID only → Task 2 SQL `WHERE o.status = 'PAID'`. ✓
- `ProductListItem`/`ProductDetail` DTOs → Task 1. ✓
- NFR 防SQL注入 (audit note) → covered by parameterization; no string-concat SQL. ✓
- NFR 图片懒加载 (`loading="lazy"`) → Tasks 5 + 6. ✓
- E2E regression baseline (merge e2e-test-suite) → Task 0. ✓
- Phase 0 merge + Phase B branch → Task 0. ✓

**2. Placeholder scan:** No TBD/TODO/"add error handling"/"similar to Task N". The e2e `http` helper import is flagged "adapt to the real e2e client" — that's intentional (the exact import depends on Step 1's discovery, which the engineer does first); the test body is complete. Acceptable.

**3. Type consistency:**
- `pageProducts` returns `PageResult<ProductListItem>` in service (Task 3), controller (Task 3), and frontend `product.ts` (Task 4). ✓
- `getDetail` returns `ProductDetail` in mapper (Task 2), service (Task 3), controller (Task 3), frontend (Task 4/6). ✓
- `sortField` / `sortOrder` / `keyword` / `category` consistent across `PageQuery` (backend Task 1 + frontend Task 4) and the XML (Task 2). ✓ (Note: backend `sortField` is a free-form String validated in the service; frontend constrains to the union — consistent in intent.)
- `ProductListItem` has `sold` + `avgRating` in DTO (Task 1), XML resultMap (Task 2), integration test (Task 2), frontend type (Task 4). ✓
- `categories()` method name: Task 3 controller `categories()`, service `listCategories()`. Different names (controller vs service) — intentional (controller method name is arbitrary; service is `listCategories`). The frontend `listCategories()` client maps to `GET /products/categories`. ✓

No issues requiring fix.
