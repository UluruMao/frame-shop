# Phase D — Reviews (UC010) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build UC010 — per-item, 3-dimension star reviews with text + images, fully editable after submit, that trigger `DELIVERED→COMPLETED` on first full-review (with a once-per-order coin-stub seam for Phase E), light up the 未评价 tab, and fill the Phase B catalog sort gap.

**Architecture:** A new `Review` entity/mapper/service/controller stack writes onto the existing (migrated + extended) `review` table. Reviews are one-per-`(order, product_id)`. The `create` service upserts, and when an order's last line item gets its first review it calls the existing Phase C `orderService.completeOrder` CAS (idempotent) + a no-op `CoinServiceStub`. Image upload reuses the existing `frame.upload.dir`/`/uploads/**` pipeline with new jpg/png + 20MB validation. Frontend gains a `ReviewModal` + active 未评价 tab + the two missing sort options.

**Tech Stack:** Java 21 Spring Boot 3, MyBatis-Plus (XML mappers), MariaDB, Redis; React 18 + TypeScript + Vite + Vitest + Testing Library + Tailwind.

## Global Constraints

- Branch `feature/phase-d-reviews` off `main` (HEAD `5442d5e`). Commit per task; commit messages end with `Co-Authored-By: Claude <noreply@anthropic.com>`.
- Backend run/test commands (from `frame-backend/`): `mvn -q test` (or `./mvnw -q test`). Per-test: `mvn -q -Dtest=ClassName test`. Integration tests hit REAL MariaDB + Redis (no Testcontainers) — they are `@SpringBootTest` with `@TestPropertySource` JWT props, same as `CheckoutFlowIntegrationTest`.
- Frontend run/test commands (from `frame-frontend/`): `npm test -- --run` (Vitest). Per-file: `npm test -- --run tests/Orders.test.tsx`. Lint: `npm run lint`. Build: `npm run build`.
- Convention: entities extend `com.iflytek.frame.common.BaseEntity` (provides `id`/`createTime`/`updateTime`, auto-filled by `MybatisMetaObjectHandler`); `@TableName("snake_case")`; `@Data @EqualsAndHashCode(callSuper = true)` Lombok. Mappers extend `BaseMapper<T>` + `@Mapper`, custom queries in `mapper/*.xml` (namespace `com.iflytek.frame.mapper.XxxMapper`). Controllers: `@RestController @RequestMapping("...") @RequiredArgsConstructor`, return `com.iflytek.frame.common.Result` via `Result.success(...)`; get the user id via `com.iflytek.frame.util.SecurityUtils.currentUserId()`.
- Errors: throw `com.iflytek.frame.exception.BusinessException(com.iflytek.frame.common.ResultCode, msg)`. Relevant codes: `NOT_FOUND` (404), `CONFLICT` (409), `PARAM_INVALID` (1001), `INTERNAL_ERROR` (500).
- Ownership-before-state: verify `order.userId == currentUserId` BEFORE checking status, and on mismatch throw `NOT_FOUND` ("order not found") — never reveal another user's order exists (Phase C convention, see `OrderServiceImpl.listItems`).
- Stars are `TINYINT`/`Integer`, validated `@Min(1) @Max(5)`. Images: jpg/png only, ≤20MB per file, ≤9 per review, stored as a JSON-array string in the `images` column.
- TDD: write the failing test → run it (red) → implement → run it (green) → commit. No step may be left as TODO/placeholder.

---

## File Structure

**Backend (new):**
- `entity/Review.java` — `@TableName("review")` entity.
- `mapper/ReviewMapper.java` + `resources/mapper/ReviewMapper.xml` — review persistence.
- `dto/ReviewCreateRequest.java`, `dto/ReviewUpdateRequest.java`, `dto/ReviewVO.java` — API shapes.
- `service/ReviewService.java` + `service/impl/ReviewServiceImpl.java` — create/upsert/update/list + COMPLETED trigger.
- `service/CoinServiceStub.java` — no-op Phase-E seam (called once per fully-reviewed order).
- `service/ReviewImageService.java` + `service/impl/ReviewImageServiceImpl.java` — jpg/png ≤20MB upload.
- `controller/ReviewController.java` — endpoints.

**Backend (modify):**
- `resources/schema.sql` — migrate `review` unique key + new columns; (no live-DB ALTER here — the dev DB is altered inline per task 0).
- `resources/application.yml` — raise multipart limits (20MB / 200MB).
- `mapper/OrderMapper.java` + `resources/mapper/OrderMapper.xml` — `selectUnreviewedOrders`.
- `service/OrderService.java` + `service/impl/OrderServiceImpl.java` — `listUnreviewedOrders` wrapper.
- `controller/OrderController.java` — `GET /orders/unreviewed`.
- `security/SecurityConfig.java` — register the new review endpoints under authenticated.

**Backend (new tests):**
- `test/.../service/ReviewServiceImplTest.java` — unit (Mockito).
- `test/.../ReviewIntegrationTest.java` — `@SpringBootTest` end-to-end.
- (extend) `test/.../mapper/ProductMapperIntegrationTest.java` — assert rating-sort reflects a new review.

**Frontend (new):**
- `src/api/review.ts` — API client.
- `src/components/ReviewModal.tsx` — the review form.
- `src/components/StarRating.tsx` — reusable 1–5 star input.
- `tests/ReviewModal.test.tsx` — component test.

**Frontend (modify):**
- `src/api/types.ts` — `Review`/`ReviewVO`/`ReviewCreateRequest`/`ReviewUpdateRequest`.
- `src/pages/Orders.tsx` — active 未评价 tab + per-item 评价 button in the drawer.
- `src/pages/Products.tsx` — add `sales|asc` + `rating|asc` sort options.
- (extend) `tests/Orders.test.tsx`, `tests/Products.test.tsx`.

**E2E (new):**
- `e2e/tests/reviews.test.mjs` — full review lifecycle against local backend.

---

## Task 0: Branch + schema migration + baseline

**Files:**
- Create: `feature/phase-d-reviews` branch off `main`.
- Modify: `frame-backend/src/main/resources/schema.sql:66-80` (the `review` CREATE TABLE).
- Modify: `frame-backend/src/main/resources/application.yml:44-48` (multipart).

**Interfaces:**
- Consumes: nothing.
- Produces: the migrated `review` table shape (columns `images`, `is_anonymous`, `edited_at`; unique key `uk_review_order_product(order_id, product_id)`) that every later task depends on; the raised multipart limits the image-upload task depends on.

- [ ] **Step 1: Create the branch + alter the live dev DB**

```bash
git checkout -b feature/phase-d-reviews main
```

Then alter the live MariaDB dev DB (same inline convention as Phase B/C — no migration tool; `application.yml` has no `spring.sql.init.mode`). Run via the `mysql` CLI with the project creds (user `root`/password from memory `frame-project-environment`, db name from `application.yml` `spring.datasource.url`):

```sql
ALTER TABLE review DROP INDEX uk_review_order;
ALTER TABLE review ADD UNIQUE KEY uk_review_order_product (order_id, product_id);
ALTER TABLE review
  ADD COLUMN images       VARCHAR(2048) DEFAULT NULL,
  ADD COLUMN is_anonymous TINYINT(1)   NOT NULL DEFAULT 0,
  ADD COLUMN edited_at    DATETIME      DEFAULT NULL;
```

Verify:
```bash
mysql -u root -p<password> <db> -e "SHOW CREATE TABLE review\G"
```
Expected: `UNIQUE KEY uk_review_order_product (order_id, product_id)`, columns `images`, `is_anonymous`, `edited_at` present.

- [ ] **Step 2: Update `schema.sql` to match the live table**

Replace the `review` CREATE TABLE (lines 66–80) with:

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
    `images`          VARCHAR(2048) DEFAULT NULL,
    `is_anonymous`    TINYINT(1)   NOT NULL DEFAULT 0,
    `edited_at`       DATETIME     DEFAULT NULL,
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_review_order_product` (`order_id`, `product_id`),
    KEY `idx_review_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- [ ] **Step 3: Raise multipart limits in `application.yml`**

Change lines 44–48 to:

```yaml
  # --- Servlet multipart (review images: jpg/png ≤20MB per file, ≤9 per review) ---
  servlet:
    multipart:
      max-file-size: 20MB
      max-request-size: 200MB
```

- [ ] **Step 4: Re-run the baseline suites (regression gate)**

```bash
cd frame-backend && mvn -q test
cd ../frame-frontend && npm test -- --run
cd ../e2e && npm test -- --run tests/catalog.test.mjs tests/orders.test.mjs   # if an e2e runner exists; else skip e2e here
```
Expected: backend 104 pass, frontend 38 pass, catalog e2e 7 pass, orders e2e 11 pass (the pre-existing non-catalog auth e2e failures are out of scope — ignore them). If a baseline suite is already red for an unrelated reason, note it in `docs/superpowers/skipped-issues.md` and proceed.

- [ ] **Step 5: Commit**

```bash
git add frame-backend/src/main/resources/schema.sql frame-backend/src/main/resources/application.yml
git commit -m "feat(phase-d): migrate review unique key to (order_id,product_id) + images/anonymous/edited_at columns; raise multipart to 20MB

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 1: `Review` entity + `ReviewMapper` (+ XML)

**Files:**
- Create: `frame-backend/src/main/java/com/iflytek/frame/entity/Review.java`
- Create: `frame-backend/src/main/java/com/iflytek/frame/mapper/ReviewMapper.java`
- Create: `frame-backend/src/main/resources/mapper/ReviewMapper.xml`
- Test: `frame-backend/src/test/java/com/iflytek/frame/mapper/ReviewMapperIntegrationTest.java`

**Interfaces:**
- Consumes: `BaseEntity` (id/createTime/updateTime), the migrated `review` table (Task 0).
- Produces: `Review` entity with fields `orderId, userId, productId, qualityStars, logisticsStars, serviceStars, content, images, isAnonymous, editedAt`; `ReviewMapper` with `int insert(Review)`, `int updateById(Review)` (inherited from `BaseMapper`), `Review selectByOrderAndProduct(Long orderId, Long productId)`, `List<Review> selectByOrderId(Long orderId)`. These are consumed by Task 3 (`ReviewServiceImpl`).

- [ ] **Step 1: Write the failing mapper integration test**

Create `ReviewMapperIntegrationTest.java` mirroring `CheckoutFlowIntegrationTest`'s `@SpringBootTest` + `@TestPropertySource` shape, but focused on the mapper:

```java
package com.iflytek.frame.mapper;

import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.OrderItem;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.entity.Review;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600"
})
class ReviewMapperIntegrationTest {

    @Autowired ReviewMapper reviewMapper;
    @Autowired com.iflytek.frame.mapper.OrderMapper orderMapper;
    @Autowired com.iflytek.frame.mapper.OrderItemMapper orderItemMapper;
    @Autowired com.iflytek.frame.mapper.ProductMapper productMapper;

    /** Seed a minimal DELIVERED order + 1 line item, return the order id + product id. */
    private long[] seedDeliveredOrder() {
        Product p = new Product();
        p.setName("ReviewMapperTest-" + System.nanoTime());
        p.setPriceInCents(1000);
        p.setStock(10);
        p.setCategory("test");
        productMapper.insert(p);

        Order o = new Order();
        o.setUserId(1L);
        o.setCustomerName("t");
        o.setCustomerEmail("t@t.com");
        o.setShippingAddress("addr");
        o.setTotalInCents(1000);
        o.setStatus(com.iflytek.frame.common.OrderStatus.DELIVERED);
        o.setIdempotencyKey("ik-" + System.nanoTime());
        orderMapper.insert(o);

        OrderItem it = new OrderItem();
        it.setOrderId(o.getId());
        it.setProductId(p.getId());
        it.setName(p.getName());
        it.setPriceInCents(1000);
        it.setQuantity(1);
        orderItemMapper.insert(it);
        return new long[]{ o.getId(), p.getId() };
    }

    @Test
    void insert_andSelectByOrderAndProduct_roundTrip() {
        long[] ids = seedDeliveredOrder();
        long orderId = ids[0], productId = ids[1];

        Review r = new Review();
        r.setOrderId(orderId);
        r.setUserId(1L);
        r.setProductId(productId);
        r.setQualityStars(4);
        r.setLogisticsStars(5);
        r.setServiceStars(3);
        r.setContent("ok");
        r.setImages("[\"/uploads/reviews/a.jpg\"]");
        r.setIsAnonymous(false);
        int rows = reviewMapper.insert(r);
        assertThat(rows).isEqualTo(1);
        assertThat(r.getId()).isNotNull();

        Review found = reviewMapper.selectByOrderAndProduct(orderId, productId);
        assertThat(found).isNotNull();
        assertThat(found.getQualityStars()).isEqualTo(4);
        assertThat(found.getImages()).isEqualTo("[\"/uploads/reviews/a.jpg\"]");
    }

    @Test
    void selectByOrderId_returnsAllForOrder() {
        long[] ids = seedDeliveredOrder();
        long orderId = ids[0], productId = ids[1];
        Review r = new Review();
        r.setOrderId(orderId); r.setUserId(1L); r.setProductId(productId);
        r.setQualityStars(5); r.setLogisticsStars(5); r.setServiceStars(5);
        reviewMapper.insert(r);
        assertThat(reviewMapper.selectByOrderId(orderId)).hasSize(1);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frame-backend && mvn -q -Dtest=ReviewMapperIntegrationTest test
```
Expected: FAIL — `Review` / `ReviewMapper` do not compile (not defined).

- [ ] **Step 3: Create the `Review` entity**

`frame-backend/src/main/java/com/iflytek/frame/entity/Review.java`:

```java
package com.iflytek.frame.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.iflytek.frame.common.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * A per-item product review (UC010). One review per (order_id, product_id) — the
 * {@code uk_review_order_product} unique key. Three 1-5 star dimensions, optional
 * text + images (JSON array of /uploads/... URLs), optional anonymity, edit tracking.
 */
@Schema(description = "Product review")
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("review")
public class Review extends BaseEntity {
    private Long orderId;
    private Long userId;
    private Long productId;
    private Integer qualityStars;
    private Integer logisticsStars;
    private Integer serviceStars;
    private String content;
    /** JSON array of image URLs (≤9), e.g. ["/uploads/reviews/x.jpg"]. Null if none. */
    private String images;
    private Boolean isAnonymous;
    /** Set on any edit-after-submit; null on first create. */
    private LocalDateTime editedAt;
}
```

- [ ] **Step 4: Create `ReviewMapper`**

`frame-backend/src/main/java/com/iflytek/frame/mapper/ReviewMapper.java`:

```java
package com.iflytek.frame.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iflytek.frame.entity.Review;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ReviewMapper extends BaseMapper<Review> {

    /** The review for a specific (order, product), or null if not yet reviewed. */
    Review selectByOrderAndProduct(@Param("orderId") Long orderId, @Param("productId") Long productId);

    /** All reviews attached to an order (to decide if the order is fully reviewed). */
    List<Review> selectByOrderId(@Param("orderId") Long orderId);
}
```

- [ ] **Step 5: Create `ReviewMapper.xml`**

`frame-backend/src/main/resources/mapper/ReviewMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.iflytek.frame.mapper.ReviewMapper">

    <select id="selectByOrderAndProduct" resultType="com.iflytek.frame.entity.Review">
        select * from review where order_id = #{orderId} and product_id = #{productId} limit 1
    </select>

    <select id="selectByOrderId" resultType="com.iflytek.frame.entity.Review">
        select * from review where order_id = #{orderId} order by create_time asc
    </select>

</mapper>
```

(`insert` + `updateById` come from `BaseMapper` — no XML needed; MyBatis-Plus generates them. `map-underscore-to-camel-case: true` maps `order_id`→`orderId` etc.)

- [ ] **Step 6: Run the test to verify it passes**

```bash
mvn -q -Dtest=ReviewMapperIntegrationTest test
```
Expected: PASS (both tests green).

- [ ] **Step 7: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/entity/Review.java \
        frame-backend/src/main/java/com/iflytek/frame/mapper/ReviewMapper.java \
        frame-backend/src/main/resources/mapper/ReviewMapper.xml \
        frame-backend/src/test/java/com/iflytek/frame/mapper/ReviewMapperIntegrationTest.java
git commit -m "feat(phase-d): Review entity + ReviewMapper (insert/selectByOrderAndProduct/selectByOrderId)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 2: Review DTOs (`ReviewCreateRequest`, `ReviewUpdateRequest`, `ReviewVO`)

**Files:**
- Create: `frame-backend/src/main/java/com/iflytek/frame/dto/ReviewCreateRequest.java`
- Create: `frame-backend/src/main/java/com/iflytek/frame/dto/ReviewUpdateRequest.java`
- Create: `frame-backend/src/main/java/com/iflytek/frame/dto/ReviewVO.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `ReviewCreateRequest` (fields: `Long orderId, Long productId, Integer qualityStars, Integer logisticsStars, Integer serviceStars, String content, List<String> imageUrls, Boolean isAnonymous`), `ReviewUpdateRequest` (same minus `orderId`/`productId`), `ReviewVO` (all review fields + `String productName` + `Boolean edited`). Consumed by Task 3's `ReviewService` signatures + Task 4's controller.

- [ ] **Step 1: Create `ReviewCreateRequest`**

```java
package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Schema(description = "Create/upsert a review for one order line item")
@Data
public class ReviewCreateRequest {
    @NotNull
    private Long orderId;
    @NotNull
    private Long productId;
    @NotNull @Min(1) @Max(5)
    private Integer qualityStars;
    @NotNull @Min(1) @Max(5)
    private Integer logisticsStars;
    @NotNull @Min(1) @Max(5)
    private Integer serviceStars;
    @Size(max = 1000)
    private String content;
    @Size(max = 9)
    private List<String> imageUrls;
    private Boolean isAnonymous;
}
```

- [ ] **Step 2: Create `ReviewUpdateRequest`**

```java
package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Schema(description = "Edit an existing review (orderId/productId are immutable)")
@Data
public class ReviewUpdateRequest {
    @Min(1) @Max(5)
    private Integer qualityStars;
    @Min(1) @Max(5)
    private Integer logisticsStars;
    @Min(1) @Max(5)
    private Integer serviceStars;
    @Size(max = 1000)
    private String content;
    @Size(max = 9)
    private List<String> imageUrls;
    private Boolean isAnonymous;
}
```

- [ ] **Step 3: Create `ReviewVO`**

```java
package com.iflytek.frame.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "A review, as returned to the client")
@Data
public class ReviewVO {
    private Long id;
    private Long orderId;
    private Long productId;
    /** Product name snapshot from order_item.name (for display without a join on the client). */
    private String productName;
    private Integer qualityStars;
    private Integer logisticsStars;
    private Integer serviceStars;
    private String content;
    /** Image URLs parsed from the stored JSON array. Empty list if none. */
    private List<String> imageUrls;
    private Boolean isAnonymous;
    private LocalDateTime editedAt;
    private LocalDateTime createTime;
    /** True if this review has been edited after its first submit. */
    @JsonProperty("edited")
    public boolean isEdited() { return editedAt != null; }
}
```

- [ ] **Step 4: Verify it compiles**

```bash
cd frame-backend && mvn -q compile
```
Expected: BUILD SUCCESS (no tests run; just confirms the DTOs + the Task-1 entity compile together).

- [ ] **Step 5: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/dto/ReviewCreateRequest.java \
        frame-backend/src/main/java/com/iflytek/frame/dto/ReviewUpdateRequest.java \
        frame-backend/src/main/java/com/iflytek/frame/dto/ReviewVO.java
git commit -m "feat(phase-d): review DTOs (create/update/VO)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 3: `CoinServiceStub` (no-op Phase-E seam)

**Files:**
- Create: `frame-backend/src/main/java/com/iflytek/frame/service/CoinServiceStub.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `CoinServiceStub.reviewSubmitted(Long orderId, Long userId)` — a no-op that logs the Phase-E intent. Consumed by Task 4's `ReviewServiceImpl.create`.

- [ ] **Step 1: Create the stub**

```java
package com.iflytek.frame.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Phase-E seam for UC012 (优惠金币). UC012 says: "用户评价后可领取等同于本订单实际付款金额的优惠金币"
 * — after reviewing, the user may claim coins equal to the order's paid amount.
 *
 * <p>Phase D calls {@link #reviewSubmitted(Long, Long)} once per order when its last line item
 * receives its first review. This stub logs the intent; Phase E replaces the body with a coin
 * ledger credit. Wrapped in try/catch by the caller so a future Phase-E failure cannot break
 * the review write.
 */
@Service
@Slf4j
public class CoinServiceStub {

    public void reviewSubmitted(Long orderId, Long userId) {
        log.info("reviewSubmitted(orderId={}, userId={}) — coin credit is Phase E (UC012)", orderId, userId);
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
cd frame-backend && mvn -q compile
```
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/service/CoinServiceStub.java
git commit -m "feat(phase-d): CoinServiceStub — no-op UC012 coin-credit seam for Phase E

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 4: `ReviewService` + impl (create upsert + COMPLETED trigger + update + list)

**Files:**
- Create: `frame-backend/src/main/java/com/iflytek/frame/service/ReviewService.java`
- Create: `frame-backend/src/main/java/com/iflytek/frame/service/impl/ReviewServiceImpl.java`
- Test: `frame-backend/src/test/java/com/iflytek/frame/service/ReviewServiceImplTest.java`

**Interfaces:**
- Consumes: `ReviewMapper` (Task 1), `Review` entity (Task 1), `ReviewCreateRequest`/`ReviewUpdateRequest`/`ReviewVO` (Task 2), `CoinServiceStub` (Task 3), `OrderService` (`getById`, `listItems`, `completeOrder` — existing), `OrderItemMapper` (existing, to list the order's items + their `name` snapshots), `ObjectMapper` (to serialize/parse `images` JSON).
- Produces: `ReviewService.create(req, userId)`, `update(reviewId, req, userId)`, `listByOrder(orderId, userId)` — all returning `ReviewVO`/`List<ReviewVO>`. Consumed by Task 6's controller.

**Implementation notes (reference the spec §4c):**
- `create` is `@Transactional`. Ownership-before-state: load order via `orderService.getById(orderId, userId)` (throws NOT_FOUND on mismatch — perfect, reuse it). Then verify `status ∈ {DELIVERED, COMPLETED}` else CONFLICT. Then verify `productId` is among the order's `orderItemMapper` items else PARAM_INVALID. Upsert via `reviewMapper.selectByOrderAndProduct`: if exists → edit path (update mutable fields, set `editedAt`, return); if absent → compute `wasFullyReviewed` before insert, insert, compute `nowFullyReviewed`, and if `!wasFullyReviewed && nowFullyReviewed` → call `orderService.completeOrder(orderId)` (CAS idempotent) + `coinServiceStub.reviewSubmitted(orderId, userId)` in try/catch.
- "Fully reviewed" = the set of reviewed `productId`s ⊇ the set of distinct `order_item.product_id`s for the order.
- `images` stored as `objectMapper.writeValueAsString(imageUrls)` (or null if empty); parsed back on read with `objectMapper.readValue(images, List.class)`.
- `update`: load review by id (`reviewMapper.selectById`), verify `review.userId == userId` else NOT_FOUND, apply non-null fields, set `editedAt = now()`, `reviewMapper.updateById`. Does NOT re-trigger COMPLETED/coin.
- `listByOrder`: ownership via `orderService.getById(orderId, userId)` (throws if not owner), then map `reviewMapper.selectByOrderId` → `ReviewVO`, filling `productName` from the matching `order_item.name`.

- [ ] **Step 1: Write the failing unit test**

`frame-backend/src/test/java/com/iflytek/frame/service/ReviewServiceImplTest.java` — Mockito, constructor-injected, mirroring `OrderServiceImplTest`:

```java
package com.iflytek.frame.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.ReviewCreateRequest;
import com.iflytek.frame.dto.ReviewVO;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.OrderItem;
import com.iflytek.frame.entity.Review;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.OrderItemMapper;
import com.iflytek.frame.mapper.ReviewMapper;
import com.iflytek.frame.service.impl.ReviewServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewServiceImplTest {

    @Mock ReviewMapper reviewMapper;
    @Mock OrderItemMapper orderItemMapper;
    @Mock OrderService orderService;
    @Mock CoinServiceStub coinServiceStub;
    ReviewServiceImpl reviewService;

    @BeforeEach
    void setUp() {
        reviewService = new ReviewServiceImpl(reviewMapper, orderItemMapper, orderService,
                coinServiceStub, new ObjectMapper());
    }

    private Order deliveredOrder() {
        Order o = new Order();
        o.setId(7L);
        o.setUserId(1L);
        o.setStatus(OrderStatus.DELIVERED);
        return o;
    }

    private OrderItem item(long pid) {
        OrderItem it = new OrderItem();
        it.setOrderId(7L);
        it.setProductId(pid);
        it.setName("P" + pid);
        return it;
    }

    private ReviewCreateRequest req(long pid) {
        ReviewCreateRequest r = new ReviewCreateRequest();
        r.setOrderId(7L);
        r.setProductId(pid);
        r.setQualityStars(5);
        r.setLogisticsStars(4);
        r.setServiceStars(3);
        r.setContent("ok");
        r.setImageUrls(List.of("/uploads/reviews/a.jpg"));
        r.setIsAnonymous(false);
        return r;
    }

    @Test
    void create_insertsAndCompletes_whenLastItemReviewed() {
        Order o = deliveredOrder();
        when(orderService.getById(7L, 1L)).thenReturn(o);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(100L), item(200L)));
        // before insert: one item already reviewed (pid 100). This create reviews pid 200 → fully reviewed.
        Review existingFor100 = new Review();
        existingFor100.setProductId(100L);
        when(reviewMapper.selectByOrderId(7L)).thenReturn(List.of(existingFor100));
        when(reviewMapper.selectByOrderAndProduct(7L, 200L)).thenReturn(null).thenReturn(stubReview(200L));

        reviewService.create(req(200L), 1L);

        verify(reviewMapper).insert(any(Review.class));
        verify(orderService).completeOrder(7L);
        verify(coinServiceStub).reviewSubmitted(7L, 1L);
    }

    @Test
    void create_doesNotComplete_whenOtherItemsUnreviewed() {
        Order o = deliveredOrder();
        when(orderService.getById(7L, 1L)).thenReturn(o);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(100L), item(200L)));
        when(reviewMapper.selectByOrderId(7L)).thenReturn(List.of()); // nothing reviewed yet
        when(reviewMapper.selectByOrderAndProduct(7L, 100L)).thenReturn(null).thenReturn(stubReview(100L));

        reviewService.create(req(100L), 1L);

        verify(orderService, never()).completeOrder(any());
        verify(coinServiceStub, never()).reviewSubmitted(any(), any());
    }

    @Test
    void create_upsertsWhenAlreadyReviewed_doesNotRecomplete() {
        Order o = deliveredOrder();
        when(orderService.getById(7L, 1L)).thenReturn(o);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(100L)));
        Review existing = stubReview(100L);
        existing.setUserId(1L);
        when(reviewMapper.selectByOrderAndProduct(7L, 100L)).thenReturn(existing);

        ReviewVO vo = reviewService.create(req(100L), 1L);

        verify(reviewMapper, never()).insert(any());
        verify(reviewMapper).updateById(any(Review.class));
        verify(orderService, never()).completeOrder(any());
        verify(coinServiceStub, never()).reviewSubmitted(any(), any());
        assertThat(vo.isEdited()).isTrue();
    }

    @Test
    void create_nonDeliverableState_throwsConflict() {
        Order o = deliveredOrder();
        o.setStatus(OrderStatus.REFUNDED);
        when(orderService.getById(7L, 1L)).thenReturn(o);
        assertThatThrownBy(() -> reviewService.create(req(100L), 1L))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getCode() == ResultCode.CONFLICT);
        verify(reviewMapper, never()).insert(any());
    }

    @Test
    void create_productNotInOrder_throwsParamInvalid() {
        when(orderService.getById(7L, 1L)).thenReturn(deliveredOrder());
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(100L))); // only 100 in order
        assertThatThrownBy(() -> reviewService.create(req(999L), 1L)) // reviewing 999
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getCode() == ResultCode.PARAM_INVALID);
    }

    @Test
    void create_acceptsCompletedOrderWithUnreviewedItems() {
        Order o = deliveredOrder();
        o.setStatus(OrderStatus.COMPLETED);
        when(orderService.getById(7L, 1L)).thenReturn(o);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(100L)));
        when(reviewMapper.selectByOrderId(7L)).thenReturn(List.of());
        when(reviewMapper.selectByOrderAndProduct(7L, 100L)).thenReturn(null).thenReturn(stubReview(100L));

        reviewService.create(req(100L), 1L);

        // completeOrder is a no-op on an already-COMPLETED order (CAS rows==0) but we still call it.
        verify(orderService).completeOrder(7L);
        verify(coinServiceStub).reviewSubmitted(7L, 1L);
    }

    private Review stubReview(long pid) {
        Review r = new Review();
        r.setId(1L);
        r.setOrderId(7L);
        r.setUserId(1L);
        r.setProductId(pid);
        r.setQualityStars(5);
        r.setLogisticsStars(4);
        r.setServiceStars(3);
        return r;
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frame-backend && mvn -q -Dtest=ReviewServiceImplTest test
```
Expected: FAIL — `ReviewService` / `ReviewServiceImpl` do not compile.

- [ ] **Step 3: Create the `ReviewService` interface**

```java
package com.iflytek.frame.service;

import com.iflytek.frame.dto.ReviewCreateRequest;
import com.iflytek.frame.dto.ReviewUpdateRequest;
import com.iflytek.frame.dto.ReviewVO;

import java.util.List;

public interface ReviewService {

    /** Create or upsert a review for one order line item (UC010). Triggers COMPLETED + coin seam
     *  when this is the order's last item's first review. */
    ReviewVO create(ReviewCreateRequest req, Long userId);

    /** Edit an existing review (any field). Does not re-trigger COMPLETED/coin. */
    ReviewVO update(Long reviewId, ReviewUpdateRequest req, Long userId);

    /** All reviews for an order (ownership-checked), with product-name snapshots. */
    List<ReviewVO> listByOrder(Long orderId, Long userId);
}
```

- [ ] **Step 4: Create `ReviewServiceImpl`**

```java
package com.iflytek.frame.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.ReviewCreateRequest;
import com.iflytek.frame.dto.ReviewUpdateRequest;
import com.iflytek.frame.dto.ReviewVO;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.OrderItem;
import com.iflytek.frame.entity.Review;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.OrderItemMapper;
import com.iflytek.frame.mapper.ReviewMapper;
import com.iflytek.frame.service.CoinServiceStub;
import com.iflytek.frame.service.OrderService;
import com.iflytek.frame.service.ReviewService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewServiceImpl implements ReviewService {

    private final ReviewMapper reviewMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderService orderService;
    private final CoinServiceStub coinServiceStub;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public ReviewVO create(ReviewCreateRequest req, Long userId) {
        // 1-2. ownership-before-state (orderService.getById throws NOT_FOUND on mismatch).
        Order order = orderService.getById(req.getOrderId(), userId);
        if (order.getStatus() != OrderStatus.DELIVERED && order.getStatus() != OrderStatus.COMPLETED) {
            throw new BusinessException(ResultCode.CONFLICT, "cannot review: order not delivered");
        }
        // 3. productId must be in the order's items.
        List<OrderItem> items = itemsOf(order.getId());
        Set<Long> orderProductIds = items.stream().map(OrderItem::getProductId).collect(Collectors.toSet());
        if (!orderProductIds.contains(req.getProductId())) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "product not in this order");
        }

        // 4. upsert
        Review existing = reviewMapper.selectByOrderAndProduct(order.getId(), req.getProductId());
        if (existing != null) {
            applyCreateToExisting(existing, req);
            reviewMapper.updateById(existing);
            return toVO(existing, items);
        }

        // 5. first-review-that-completes-all-items → COMPLETED + coin seam
        boolean wasFullyReviewed = isFullyReviewed(order.getId(), orderProductIds);
        Review r = fromCreate(req, order.getUserId());
        reviewMapper.insert(r);
        boolean nowFullyReviewed = isFullyReviewed(order.getId(), orderProductIds);
        if (!wasFullyReviewed && nowFullyReviewed) {
            try {
                orderService.completeOrder(order.getId());   // CAS idempotent; no-op if already COMPLETED
            } catch (Exception e) {
                log.warn("completeOrder({}) failed after review: {}", order.getId(), e.getMessage());
            }
            try {
                coinServiceStub.reviewSubmitted(order.getId(), userId);
            } catch (Exception e) {
                log.warn("coinServiceStub.reviewSubmitted({}) failed: {}", order.getId(), e.getMessage());
            }
        }
        return toVO(r, items);
    }

    @Override
    @Transactional
    public ReviewVO update(Long reviewId, ReviewUpdateRequest req, Long userId) {
        Review r = reviewMapper.selectById(reviewId);
        if (r == null || !r.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "review not found");
        }
        if (req.getQualityStars() != null) r.setQualityStars(req.getQualityStars());
        if (req.getLogisticsStars() != null) r.setLogisticsStars(req.getLogisticsStars());
        if (req.getServiceStars() != null) r.setServiceStars(req.getServiceStars());
        if (req.getContent() != null) r.setContent(req.getContent());
        if (req.getImageUrls() != null) r.setImages(toImagesJson(req.getImageUrls()));
        if (req.getIsAnonymous() != null) r.setIsAnonymous(req.getIsAnonymous());
        r.setEditedAt(LocalDateTime.now());
        reviewMapper.updateById(r);
        return toVO(r, itemsOf(r.getOrderId()));
    }

    @Override
    public List<ReviewVO> listByOrder(Long orderId, Long userId) {
        orderService.getById(orderId, userId); // ownership check (NOT_FOUND on mismatch)
        List<OrderItem> items = itemsOf(orderId);
        Map<Long, String> nameByPid = items.stream()
                .collect(Collectors.toMap(OrderItem::getProductId, OrderItem::getName, (a, b) -> a));
        return reviewMapper.selectByOrderId(orderId).stream()
                .map(r -> toVO(r, items))
                .toList();
    }

    // --- helpers ---

    private List<OrderItem> itemsOf(Long orderId) {
        return orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));
    }

    private boolean isFullyReviewed(Long orderId, Set<Long> orderProductIds) {
        Set<Long> reviewed = reviewMapper.selectByOrderId(orderId).stream()
                .map(Review::getProductId).collect(Collectors.toSet());
        return reviewed.containsAll(orderProductIds);
    }

    private void applyCreateToExisting(Review existing, ReviewCreateRequest req) {
        existing.setQualityStars(req.getQualityStars());
        existing.setLogisticsStars(req.getLogisticsStars());
        existing.setServiceStars(req.getServiceStars());
        if (req.getContent() != null) existing.setContent(req.getContent());
        existing.setImages(toImagesJson(req.getImageUrls()));
        if (req.getIsAnonymous() != null) existing.setIsAnonymous(req.getIsAnonymous());
        existing.setEditedAt(LocalDateTime.now());
    }

    private Review fromCreate(ReviewCreateRequest req, Long userId) {
        Review r = new Review();
        r.setOrderId(req.getOrderId());
        r.setUserId(userId);
        r.setProductId(req.getProductId());
        r.setQualityStars(req.getQualityStars());
        r.setLogisticsStars(req.getLogisticsStars());
        r.setServiceStars(req.getServiceStars());
        r.setContent(req.getContent());
        r.setImages(toImagesJson(req.getImageUrls()));
        r.setIsAnonymous(Boolean.TRUE.equals(req.getIsAnonymous()));
        return r;
    }

    private String toImagesJson(List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(imageUrls);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "image serialization failed");
        }
    }

    private List<String> fromImagesJson(String images) {
        if (images == null || images.isBlank()) return List.of();
        try {
            return objectMapper.readValue(images, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private ReviewVO toVO(Review r, List<OrderItem> items) {
        ReviewVO vo = new ReviewVO();
        vo.setId(r.getId());
        vo.setOrderId(r.getOrderId());
        vo.setProductId(r.getProductId());
        vo.setProductName(items.stream()
                .filter(it -> it.getProductId().equals(r.getProductId()))
                .map(OrderItem::getName).findFirst().orElse(null));
        vo.setQualityStars(r.getQualityStars());
        vo.setLogisticsStars(r.getLogisticsStars());
        vo.setServiceStars(r.getServiceStars());
        vo.setContent(r.getContent());
        vo.setImageUrls(fromImagesJson(r.getImages()));
        vo.setIsAnonymous(r.getIsAnonymous());
        vo.setEditedAt(r.getEditedAt());
        vo.setCreateTime(r.getCreateTime());
        return vo;
    }
}
```

- [ ] **Step 5: Run the unit test to verify it passes**

```bash
mvn -q -Dtest=ReviewServiceImplTest test
```
Expected: PASS (all 6 tests green).

- [ ] **Step 6: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/service/ReviewService.java \
        frame-backend/src/main/java/com/iflytek/frame/service/impl/ReviewServiceImpl.java \
        frame-backend/src/test/java/com/iflytek/frame/service/ReviewServiceImplTest.java
git commit -m "feat(phase-d): ReviewService — create upsert + DELIVERED→COMPLETED trigger + once-per-order coin seam + update/list

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 5: `ReviewImageService` (jpg/png ≤20MB upload)

**Files:**
- Create: `frame-backend/src/main/java/com/iflytek/frame/service/ReviewImageService.java`
- Create: `frame-backend/src/main/java/com/iflytek/frame/service/impl/ReviewImageServiceImpl.java`
- Test: `frame-backend/src/test/java/com/iflytek/frame/service/ReviewImageServiceImplTest.java`

**Interfaces:**
- Consumes: `frame.upload.dir` (config).
- Produces: `ReviewImageService.upload(MultipartFile file)` → `String` (URL like `/uploads/reviews/{filename}`). Consumed by Task 6's controller.

- [ ] **Step 1: Write the failing unit test**

```java
package com.iflytek.frame.service;

import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.service.impl.ReviewImageServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewImageServiceImplTest {

    @TempDir Path tempDir;
    ReviewImageServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ReviewImageServiceImpl();
        // inject the temp dir via reflection (field is @Value-injected in prod)
        org.springframework.test.util.ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());
    }

    private MultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("file", name, contentType, content);
    }

    @Test
    void upload_jpg_returnsReviewsUrl() {
        String url = service.upload(file("cat.jpg", "image/jpeg", new byte[]{1, 2, 3}));
        assertThat(url).startsWith("/uploads/reviews/review_");
        assertThat(url).endsWith(".jpg");
    }

    @Test
    void upload_png_ok() {
        assertThat(service.upload(file("p.PNG", "image/png", new byte[]{9})).endsWith(".png") ||
                service.upload(file("p.PNG", "image/png", new byte[]{9})).endsWith(".PNG")).isTrue();
    }

    @Test
    void upload_empty_rejected() {
        assertThatThrownBy(() -> service.upload(file("x.jpg", "image/jpeg", new byte[0])))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getCode() == ResultCode.PARAM_INVALID);
    }

    @Test
    void upload_oversize_rejected() {
        byte[] big = new byte[20 * 1024 * 1024 + 1];
        assertThatThrownBy(() -> service.upload(file("big.jpg", "image/jpeg", big)))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getCode() == ResultCode.PARAM_INVALID);
    }

    @Test
    void upload_badType_rejected() {
        assertThatThrownBy(() -> service.upload(file("evil.gif", "image/gif", new byte[]{1})))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getCode() == ResultCode.PARAM_INVALID);
    }

    @Test
    void upload_typeJpegButExtTxt_rejected() {
        assertThatThrownBy(() -> service.upload(file("evil.txt", "image/jpeg", new byte[]{1})))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getCode() == ResultCode.PARAM_INVALID);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frame-backend && mvn -q -Dtest=ReviewImageServiceImplTest test
```
Expected: FAIL — `ReviewImageService`/impl not defined.

- [ ] **Step 3: Create the interface**

```java
package com.iflytek.frame.service;

import org.springframework.web.multipart.MultipartFile;

/** Validates + stores a review image (jpg/png ≤20MB). Returns its servable URL. */
public interface ReviewImageService {
    String upload(MultipartFile file);
}
```

- [ ] **Step 4: Create the impl**

```java
package com.iflytek.frame.service.impl;

import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.service.ReviewImageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class ReviewImageServiceImpl implements ReviewImageService {

    @Value("${frame.upload.dir:./uploads}")
    private String uploadDir;

    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXT = Set.of(".jpg", ".jpeg", ".png");
    private static final Set<String> ALLOWED_TYPE = Set.of("image/jpeg", "image/png");

    @Override
    public String upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "file is empty");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "image exceeds 20MB");
        }
        String contentType = file.getContentType();
        String original = file.getOriginalFilename();
        String ext = "";
        if (original != null && original.contains(".")) {
            ext = original.substring(original.lastIndexOf('.')).toLowerCase();
        }
        if (contentType == null || !ALLOWED_TYPE.contains(contentType) || !ALLOWED_EXT.contains(ext)) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "only jpg/png allowed");
        }
        try {
            Path dir = Path.of(uploadDir, "reviews");
            Files.createDirectories(dir);
            String filename = "review_" + UUID.randomUUID() + ext;
            Files.copy(file.getInputStream(), dir.resolve(filename));
            return "/uploads/reviews/" + filename;
        } catch (Exception e) {
            log.error("review image upload failed", e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "image upload failed");
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
mvn -q -Dtest=ReviewImageServiceImplTest test
```
Expected: PASS (all 6 tests green).

- [ ] **Step 6: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/service/ReviewImageService.java \
        frame-backend/src/main/java/com/iflytek/frame/service/impl/ReviewImageServiceImpl.java \
        frame-backend/src/test/java/com/iflytek/frame/service/ReviewImageServiceImplTest.java
git commit -m "feat(phase-d): ReviewImageService — jpg/png ≤20MB validation + /uploads/reviews storage

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 6: `ReviewController` + `SecurityConfig` + 未reviewed query (`OrderMapper`/`OrderService`/`OrderController`)

**Files:**
- Create: `frame-backend/src/main/java/com/iflytek/frame/controller/ReviewController.java`
- Modify: `frame-backend/src/main/java/com/iflytek/frame/mapper/OrderMapper.java` (+ XML)
- Modify: `frame-backend/src/main/resources/mapper/OrderMapper.xml`
- Modify: `frame-backend/src/main/java/com/iflytek/frame/service/OrderService.java`
- Modify: `frame-backend/src/main/java/com/iflytek/frame/service/impl/OrderServiceImpl.java`
- Modify: `frame-backend/src/main/java/com/iflytek/frame/controller/OrderController.java`
- Modify: `frame-backend/src/main/java/com/iflytek/frame/security/SecurityConfig.java`
- Test: `frame-backend/src/test/java/com/iflytek/frame/ReviewIntegrationTest.java`

**Interfaces:**
- Consumes: `ReviewService` (Task 4), `ReviewImageService` (Task 5), `OrderService`, `SecurityUtils`.
- Produces: endpoints `GET /orders/{orderId}/reviews`, `POST /orders/{orderId}/reviews`, `PUT /reviews/{reviewId}`, `GET /orders/unreviewed`, `POST /reviews/images`. The `GET /orders/unreviewed` is backed by `OrderMapper.selectUnreviewedOrders(Long userId)` + `OrderService.listUnreviewedOrders(Long userId)`.

- [ ] **Step 1: Write the failing integration test**

`frame-backend/src/test/java/com/iflytek/frame/ReviewIntegrationTest.java` — `@SpringBootTest` end-to-end, mirroring `CheckoutFlowIntegrationTest`. Seeds a user, a product, a DELIVERED order with one item, then drives the review flow:

```java
package com.iflytek.frame;

import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.dto.*;
import com.iflytek.frame.entity.*;
import com.iflytek.frame.mapper.*;
import com.iflytek.frame.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600"
})
class ReviewIntegrationTest {

    @Autowired ReviewService reviewService;
    @Autowired OrderService orderService;
    @Autowired ProductMapper productMapper;
    @Autowired OrderMapper orderMapper;
    @Autowired OrderItemMapper orderItemMapper;
    @Autowired UserService userService;
    @Autowired StringRedisTemplate redis;

    private Long registerUser() {
        String phone = "139" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        redis.opsForValue().set("sms:code:REGISTER:" + phone, "123456", Duration.ofSeconds(300));
        User u = userService.register(new RegisterRequest(
                "rev" + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
                "Pass1234!", phone, "123456", true));
        return u.getId();
    }

    private Long seedDeliveredOrder(Long userId) {
        Product p = new Product();
        p.setName("RevIntTest-" + System.nanoTime());
        p.setPriceInCents(2000);
        p.setStock(5);
        p.setCategory("test");
        productMapper.insert(p);

        Order o = new Order();
        o.setUserId(userId);
        o.setCustomerName("t");
        o.setCustomerEmail("t@t.com");
        o.setShippingAddress("addr");
        o.setTotalInCents(2000);
        o.setStatus(OrderStatus.DELIVERED);
        o.setIdempotencyKey("ik-" + System.nanoTime());
        orderMapper.insert(o);

        OrderItem it = new OrderItem();
        it.setOrderId(o.getId());
        it.setProductId(p.getId());
        it.setName(p.getName());
        it.setPriceInCents(2000);
        it.setQuantity(1);
        orderItemMapper.insert(it);
        return o.getId();
    }

    @Test
    void review_completesOrder_andFeedsRatingSort() {
        Long userId = registerUser();
        Long orderId = seedDeliveredOrder(userId);

        ReviewCreateRequest req = new ReviewCreateRequest();
        req.setOrderId(orderId);
        // productId resolved from the order's item
        List<OrderItem> items = orderService.listItems(orderId, userId);
        req.setProductId(items.get(0).getProductId());
        req.setQualityStars(5); req.setLogisticsStars(5); req.setServiceStars(5);
        req.setContent("great"); req.setImageUrls(List.of()); req.setIsAnonymous(false);

        ReviewVO vo = reviewService.create(req, userId);
        assertThat(vo.getId()).isNotNull();

        // order is now COMPLETED (the only item was reviewed)
        assertThat(orderService.getById(orderId, userId).getStatus()).isEqualTo(OrderStatus.COMPLETED);

        // 未评价 tab: this order no longer appears (it's fully reviewed)
        assertThat(orderService.listUnreviewedOrders(userId))
                .extracting(Order::getId).doesNotContain(orderId);
    }

    @Test
    void unreviewedOrder_appearsInTab_thenDisappearsAfterReview() {
        Long userId = registerUser();
        Long orderId = seedDeliveredOrder(userId);
        assertThat(orderService.listUnreviewedOrders(userId))
                .extracting(Order::getId).contains(orderId);

        ReviewCreateRequest req = new ReviewCreateRequest();
        req.setOrderId(orderId);
        req.setProductId(orderService.listItems(orderId, userId).get(0).getProductId());
        req.setQualityStars(4); req.setLogisticsStars(4); req.setServiceStars(4);
        reviewService.create(req, userId);

        assertThat(orderService.listUnreviewedOrders(userId))
                .extracting(Order::getId).doesNotContain(orderId);
    }

    @Test
    void edit_afterSubmit_updatesReview() {
        Long userId = registerUser();
        Long orderId = seedDeliveredOrder(userId);
        ReviewCreateRequest req = new ReviewCreateRequest();
        req.setOrderId(orderId);
        req.setProductId(orderService.listItems(orderId, userId).get(0).getProductId());
        req.setQualityStars(3); req.setLogisticsStars(3); req.setServiceStars(3);
        ReviewVO vo = reviewService.create(req, userId);

        ReviewUpdateRequest upd = new ReviewUpdateRequest();
        upd.setQualityStars(5); upd.setLogisticsStars(5); upd.setServiceStars(5);
        upd.setContent("edited text");
        ReviewVO updated = reviewService.update(vo.getId(), upd, userId);
        assertThat(updated.getQualityStars()).isEqualTo(5);
        assertThat(updated.isEdited()).isTrue();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frame-backend && mvn -q -Dtest=ReviewIntegrationTest test
```
Expected: FAIL — `OrderService.listUnreviewedOrders` does not exist (compile error).

- [ ] **Step 3: Add `selectUnreviewedOrders` to `OrderMapper` + XML**

In `OrderMapper.java`, add after `selectDeliveredBefore`:

```java
    /**
     * Orders in {DELIVERED, COMPLETED} for a user that have ≥1 unreviewed line item —
     * backs the 未评价 tab (UC010). An order is "unreviewed" if any of its order_item
     * product_ids has no matching review row.
     */
    List<Order> selectUnreviewedOrders(@Param("userId") Long userId);
```

(Add `import java.util.List;` if not present — it already is.)

In `OrderMapper.xml`, add before `</mapper>`:

```xml
    <select id="selectUnreviewedOrders" resultType="com.iflytek.frame.entity.Order">
        select o.* from orders o
        where o.user_id = #{userId}
          and o.status in ('DELIVERED','COMPLETED')
          and exists (
            select 1 from order_item oi
            where oi.order_id = o.id
              and not exists (
                select 1 from review r
                where r.order_id = o.id and r.product_id = oi.product_id
              )
          )
        order by o.create_time desc
    </select>
```

- [ ] **Step 4: Add `listUnreviewedOrders` to `OrderService` + impl**

In `OrderService.java` interface, add:

```java
    /** 未评价 tab: the user's {DELIVERED, COMPLETED} orders with ≥1 unreviewed line item. */
    List<Order> listUnreviewedOrders(Long userId);
```

In `OrderServiceImpl.java`, add (it already has `orderMapper` injected):

```java
    @Override
    public List<Order> listUnreviewedOrders(Long userId) {
        return orderMapper.selectUnreviewedOrders(userId);
    }
```

(Add `import java.util.List;` if not present.)

- [ ] **Step 5: Add `GET /orders/unreviewed` to `OrderController`**

In `OrderController.java`, add (the class already imports `List`, `Result`, `Order`):

```java
    @Operation(summary = "未评价 tab: delivered/completed orders with unreviewed items")
    @GetMapping("/unreviewed")
    public Result<List<Order>> unreviewed() {
        return Result.success(orderService.listUnreviewedOrders(SecurityUtils.currentUserId()));
    }
```

- [ ] **Step 6: Create `ReviewController`**

`frame-backend/src/main/java/com/iflytek/frame/controller/ReviewController.java`:

```java
package com.iflytek.frame.controller;

import com.iflytek.frame.common.Result;
import com.iflytek.frame.dto.ReviewCreateRequest;
import com.iflytek.frame.dto.ReviewUpdateRequest;
import com.iflytek.frame.dto.ReviewVO;
import com.iflytek.frame.service.ReviewImageService;
import com.iflytek.frame.service.ReviewService;
import com.iflytek.frame.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "Review", description = "UC010 product reviews (per order line item)")
@RestController
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;
    private final ReviewImageService reviewImageService;

    @Operation(summary = "List reviews for an order (drawer / edit view)")
    @GetMapping("/orders/{orderId}/reviews")
    public Result<List<ReviewVO>> listByOrder(@PathVariable Long orderId) {
        return Result.success(reviewService.listByOrder(orderId, SecurityUtils.currentUserId()));
    }

    @Operation(summary = "Create/upsert a review for an order line item")
    @PostMapping("/orders/{orderId}/reviews")
    public Result<ReviewVO> create(@PathVariable Long orderId, @Valid @RequestBody ReviewCreateRequest req) {
        // path orderId is authoritative; ignore a conflicting body orderId
        req.setOrderId(orderId);
        return Result.success(reviewService.create(req, SecurityUtils.currentUserId()));
    }

    @Operation(summary = "Edit an existing review")
    @PutMapping("/reviews/{reviewId}")
    public Result<ReviewVO> update(@PathVariable Long reviewId, @Valid @RequestBody ReviewUpdateRequest req) {
        return Result.success(reviewService.update(reviewId, req, SecurityUtils.currentUserId()));
    }

    @Operation(summary = "Upload a review image (jpg/png ≤20MB)")
    @PostMapping("/reviews/images")
    public Result<String> uploadImage(@RequestParam("file") MultipartFile file) {
        return Result.success(reviewImageService.upload(file));
    }
}
```

- [ ] **Step 7: Register endpoints in `SecurityConfig`**

The new endpoints are under `anyRequest().authenticated()` already — no matcher needed for them (they require a logged-in user, which `.anyRequest().authenticated()` covers). `/uploads/reviews/**` is already covered by the existing `GET /uploads/**` `permitAll()` matcher. **No SecurityConfig change required.** (Verify by reading the file: lines 100–124 already cover GET `/uploads/**` permitAll + `.anyRequest().authenticated()`.)

If a future reviewer insists on an explicit matcher, the equivalent would be inserted before `.anyRequest().authenticated()`:
```java
.requestMatchers(HttpMethod.GET, "/orders/unreviewed", "/orders/{orderId}/reviews").authenticated()
.requestMatchers(HttpMethod.POST, "/orders/{orderId}/reviews", "/reviews/images").authenticated()
.requestMatchers(HttpMethod.PUT, "/reviews/{reviewId}").authenticated()
```
—but this is redundant with `anyRequest().authenticated()` and is NOT added (avoid noise; match the existing style where user order endpoints like `/orders/me`, `/orders/{id}/items` also rely on the catch-all).

- [ ] **Step 8: Run the integration test to verify it passes**

```bash
mvn -q -Dtest=ReviewIntegrationTest test
```
Expected: PASS (all 3 tests green).

- [ ] **Step 9: Run the full backend suite (regression)**

```bash
mvn -q test
```
Expected: all green (104 prior + new review tests). No regressions in `OrderServiceImplTest` (we only added a method).

- [ ] **Step 10: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/controller/ReviewController.java \
        frame-backend/src/main/java/com/iflytek/frame/mapper/OrderMapper.java \
        frame-backend/src/main/resources/mapper/OrderMapper.xml \
        frame-backend/src/main/java/com/iflytek/frame/service/OrderService.java \
        frame-backend/src/main/java/com/iflytek/frame/service/impl/OrderServiceImpl.java \
        frame-backend/src/main/java/com/iflytek/frame/controller/OrderController.java \
        frame-backend/src/test/java/com/iflytek/frame/ReviewIntegrationTest.java
git commit -m "feat(phase-d): ReviewController + GET /orders/unreviewed + OrderMapper.selectUnreviewedOrders

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 7: Extend `ProductMapperIntegrationTest` — rating-sort reflects a new review

**Files:**
- Modify: `frame-backend/src/test/java/com/iflytek/frame/mapper/ProductMapperIntegrationTest.java`

**Interfaces:**
- Consumes: `ProductMapper.pageProducts` (existing), `ReviewMapper` (Task 1), the migrated `review` table (Task 0).
- Produces: a regression assertion that writing a review changes a product's `avgRating` in the catalog query (the existing AVG JOIN in `ProductMapper.xml`).

- [ ] **Step 1: Read the existing test to match its style**

```bash
sed -n '1,80p' frame-backend/src/test/java/com/iflytek/frame/mapper/ProductMapperIntegrationTest.java
```
Note its `@SpringBootTest`/`@TestPropertySource` header, how it seeds a product, and how it calls `pageProducts` with a `PageQuery` + sort.

- [ ] **Step 2: Write the failing test (add a method)**

Add a test that: seeds a product with `stock`, asserts its `avgRating == 0.0` (no reviews), inserts a review (5/5/5 → avg 5.0) for that product via `ReviewMapper`, then re-queries and asserts `avgRating == 5.0` and that a `rating|desc` sort surfaces it. Use the same seeding helpers the existing test already defines; if it lacks a product-seed helper, inline one like Task 1's `seedDeliveredOrder` product half (without the order).

```java
    @Test
    void ratingSort_reflectsNewReview() {
        // seed product
        Product p = new Product();
        p.setName("RatingSortTest-" + System.nanoTime());
        p.setPriceInCents(500);
        p.setStock(3);
        p.setCategory("test");
        productMapper.insert(p);

        // before review: avgRating 0
        com.iflytek.frame.util.PageQuery q = new com.iflytek.frame.util.PageQuery();
        q.setSortField("rating"); q.setSortOrder("desc"); q.setCurrent(1); q.setSize(50);
        var before = productMapper.pageProducts(q.getMybatisPage(), q);
        double beforeAvg = before.getRecords().stream()
                .filter(x -> x.getId().equals(p.getId()))
                .findFirst().map(com.iflytek.frame.dto.ProductListItem::getAvgRating).orElse(-1.0);
        assertThat(beforeAvg).isEqualTo(0.0);

        // insert a 5/5/5 review for this product
        Review r = new Review();
        r.setOrderId(-1L); r.setUserId(-1L); r.setProductId(p.getId());
        r.setQualityStars(5); r.setLogisticsStars(5); r.setServiceStars(5);
        reviewMapper.insert(r);

        // after review: avgRating 5.0  (note: orderId/userId=-1 are synthetic; the AVG JOIN only reads stars)
        var after = productMapper.pageProducts(q.getMybatisPage(), q);
        double afterAvg = after.getRecords().stream()
                .filter(x -> x.getId().equals(p.getId()))
                .findFirst().map(com.iflytek.frame.dto.ProductListItem::getAvgRating).orElse(-1.0);
        assertThat(afterAvg).isEqualTo(5.0);
    }
```

(If the existing `PageQuery` exposes page/sort differently — e.g. a builder or different accessor names — adapt the query construction to match; the goal is: sortField=rating, sortOrder=desc, page a product by id, read its avgRating before/after a review insert. Also add `@Autowired ReviewMapper reviewMapper;` to the test class.)

- [ ] **Step 3: Run the test to verify it fails (or passes — the JOIN already exists)**

```bash
mvn -q -Dtest=ProductMapperIntegrationTest test
```
Expected: PASS (the `ProductMapper.xml` AVG JOIN already exists from Phase B — this test is a regression guard proving the review table feeds it). If it fails because `ReviewMapper` isn't autowired or `PageQuery` accessors differ, fix the test to match the real API (read `PageQuery.java` + how the existing test calls `pageProducts`).

- [ ] **Step 4: Commit**

```bash
git add frame-backend/src/test/java/com/iflytek/frame/mapper/ProductMapperIntegrationTest.java
git commit -m "test(phase-d): assert catalog rating-sort reflects a newly written review

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 8: Frontend types + `api/review.ts`

**Files:**
- Modify: `frame-frontend/src/api/types.ts` (add review types after the `OrderItem` block ~line 96).
- Create: `frame-frontend/src/api/review.ts`

**Interfaces:**
- Consumes: `./request` (the axios instance), `Order`/`OrderItem` types.
- Produces: `Review`/`ReviewVO`/`ReviewCreateRequest`/`ReviewUpdateRequest` types + `listOrderReviews`, `createReview`, `updateReview`, `listUnreviewedOrders`, `uploadReviewImage` functions. Consumed by Task 10 (Orders.tsx) + Task 9 (ReviewModal).

- [ ] **Step 1: Add review types to `types.ts`**

After the `OrderItem` interface (line 96), add:

```ts
/** A product review (UC010). One per (order, product). Mirrors backend ReviewVO. */
export interface Review {
  id: number
  orderId: number
  productId: number
  productName?: string
  qualityStars: number
  logisticsStars: number
  serviceStars: number
  content?: string
  imageUrls: string[]
  isAnonymous: boolean
  editedAt?: string
  createTime?: string
  /** Derived: true if the review was edited after first submit. */
  edited: boolean
}

export interface ReviewCreateRequest {
  orderId: number
  productId: number
  qualityStars: number
  logisticsStars: number
  serviceStars: number
  content?: string
  imageUrls?: string[]
  isAnonymous?: boolean
}

export interface ReviewUpdateRequest {
  qualityStars?: number
  logisticsStars?: number
  serviceStars?: number
  content?: string
  imageUrls?: string[]
  isAnonymous?: boolean
}
```

- [ ] **Step 2: Create `api/review.ts`**

```ts
import request from './request'
import type { Order, Review, ReviewCreateRequest, ReviewUpdateRequest } from './types'

/** GET /orders/{orderId}/reviews — reviews for an order (the drawer's edit view). */
export function listOrderReviews(orderId: number): Promise<Review[]> {
  return request.get<Review[]>(`/orders/${orderId}/reviews`)
}

/** POST /orders/{orderId}/reviews — create/upsert a review for a line item. */
export function createReview(orderId: number, req: ReviewCreateRequest): Promise<Review> {
  return request.post<Review>(`/orders/${orderId}/reviews`, req)
}

/** PUT /reviews/{reviewId} — edit an existing review. */
export function updateReview(reviewId: number, req: ReviewUpdateRequest): Promise<Review> {
  return request.put<Review>(`/reviews/${reviewId}`, req)
}

/** GET /orders/unreviewed — the 未评价 tab: delivered/completed orders with unreviewed items. */
export function listUnreviewedOrders(): Promise<Order[]> {
  return request.get<Order[]>('/orders/unreviewed')
}

/** POST /reviews/images — upload one jpg/png ≤20MB; returns the servable URL. */
export function uploadReviewImage(file: File): Promise<string> {
  const form = new FormData()
  form.append('file', file)
  return request.post<string>('/reviews/images', form)
}
```

- [ ] **Step 3: Verify it type-checks + builds**

```bash
cd frame-frontend && npm run build
```
Expected: build succeeds (no TS errors). (If `npm run build` also runs tests, fine; the new code has no tests yet — Task 9/11 cover them.)

- [ ] **Step 4: Commit**

```bash
git add frame-frontend/src/api/types.ts frame-frontend/src/api/review.ts
git commit -m "feat(phase-d): frontend review types + api/review.ts client

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 9: `StarRating` + `ReviewModal` components (+ test)

**Files:**
- Create: `frame-frontend/src/components/StarRating.tsx`
- Create: `frame-frontend/src/components/ReviewModal.tsx`
- Test: `frame-frontend/tests/ReviewModal.test.tsx`

**Interfaces:**
- Consumes: `@/api/review` (`createReview`, `updateReview`, `uploadReviewImage`), `@/api/types` (`Review`, `ReviewCreateRequest`).
- Produces: `<ReviewModal orderId productId existingReview onClose onSuccess />` — opens for a given order line item; prefills from `existingReview` if editing; submits create or update. Consumed by Task 10 (Orders.tsx drawer).

- [ ] **Step 1: Write the failing component test**

`frame-frontend/tests/ReviewModal.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import ReviewModal from '@/components/ReviewModal'

vi.mock('@/api/review', () => ({
  createReview: vi.fn(),
  updateReview: vi.fn(),
  uploadReviewImage: vi.fn(),
  listOrderReviews: vi.fn(),
  listUnreviewedOrders: vi.fn(),
}))
import { createReview, updateReview } from '@/api/review'
import type { Review } from '@/api/types'

function existing(): Review {
  return {
    id: 99, orderId: 1, productId: 100, productName: 'Widget',
    qualityStars: 3, logisticsStars: 3, serviceStars: 3,
    content: 'old', imageUrls: [], isAnonymous: false, edited: false,
  }
}

describe('ReviewModal', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders 3 star rows + submit; create calls createReview', async () => {
    const onCreate = vi.fn()
    ;(createReview as any).mockResolvedValue({ id: 1 })
    render(<ReviewModal orderId={1} productId={100} existing={null} onClose={() => {}} onSuccess={onCreate} />)
    expect(screen.getAllByRole('button', { name: /star/i }).length).toBeGreaterThanOrEqual(15) // 3 rows × 5
    fireEvent.click(screen.getByText('提交评价'))
    await waitFor(() => expect(createReview).toHaveBeenCalledTimes(1))
    expect(onCreate).toHaveBeenCalled()
  })

  it('editing prefills existing values and calls updateReview', async () => {
    const onUpdate = vi.fn()
    ;(updateReview as any).mockResolvedValue({ id: 99 })
    render(<ReviewModal orderId={1} productId={100} existing={existing()} onClose={() => {}} onSuccess={onUpdate} />)
    fireEvent.click(screen.getByText('提交评价'))
    await waitFor(() => expect(updateReview).toHaveBeenCalledWith(99, expect.any(Object)))
    expect(onUpdate).toHaveBeenCalled()
  })

  it('close button calls onClose', () => {
    const onClose = vi.fn()
    render(<ReviewModal orderId={1} productId={100} existing={null} onClose={onClose} onSuccess={() => {}} />)
    fireEvent.click(screen.getByText('取消'))
    expect(onClose).toHaveBeenCalled()
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frame-frontend && npm test -- --run tests/ReviewModal.test.tsx
```
Expected: FAIL — `@/components/ReviewModal` (and `StarRating`) do not exist.

- [ ] **Step 3: Create `StarRating.tsx`**

```tsx
interface StarRatingProps {
  value: number
  onChange: (v: number) => void
  label: string
}

/** A 1-5 star input row (商品质量 / 物流速度 / 客服态度). */
export default function StarRating({ value, onChange, label }: StarRatingProps) {
  return (
    <div className="flex items-center gap-2">
      <span className="w-20 text-sm text-slate-600">{label}</span>
      <div className="flex gap-1">
        {[1, 2, 3, 4, 5].map((n) => (
          <button
            key={n}
            type="button"
            aria-label={`star ${n}`}
            onClick={() => onChange(n)}
            className={`text-2xl ${n <= value ? 'text-amber-500' : 'text-slate-300'}`}
          >
            ★
          </button>
        ))}
      </div>
    </div>
  )
}
```

- [ ] **Step 4: Create `ReviewModal.tsx`**

```tsx
import { useState } from 'react'
import { X } from 'lucide-react'
import StarRating from './StarRating'
import { createReview, updateReview, uploadReviewImage } from '@/api/review'
import type { Review } from '@/api/types'

interface ReviewModalProps {
  orderId: number
  productId: number
  existing: Review | null
  onClose: () => void
  onSuccess: () => void
}

const MAX_IMAGES = 9

export default function ReviewModal({ orderId, productId, existing, onClose, onSuccess }: ReviewModalProps) {
  const [quality, setQuality] = useState(existing?.qualityStars ?? 5)
  const [logistics, setLogistics] = useState(existing?.logisticsStars ?? 5)
  const [service, setService] = useState(existing?.serviceStars ?? 5)
  const [content, setContent] = useState(existing?.content ?? '')
  const [imageUrls, setImageUrls] = useState<string[]>(existing?.imageUrls ?? [])
  const [anonymous, setAnonymous] = useState(existing?.isAnonymous ?? false)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)

  async function onPickImages(files: FileList | null) {
    if (!files) return
    const room = MAX_IMAGES - imageUrls.length
    const picked = Array.from(files).slice(0, room)
    try {
      const urls = await Promise.all(picked.map((f) => uploadReviewImage(f)))
      setImageUrls((prev) => [...prev, ...urls])
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'image upload failed')
    }
  }

  async function submit() {
    setBusy(true); setErr(null)
    try {
      const payload = { qualityStars: quality, logisticsStars: logistics, serviceStars: service,
        content: content || undefined, imageUrls, isAnonymous: anonymous }
      if (existing) {
        await updateReview(existing.id, payload)
      } else {
        await createReview(orderId, { orderId, productId, ...payload })
      }
      onSuccess()
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'submit failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="w-full max-w-md rounded-lg bg-white p-5 shadow-lg">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-lg font-bold">{existing ? '编辑评价' : '写评价'}</h2>
          <button onClick={onClose}><X className="h-5 w-5 text-slate-400" /></button>
        </div>
        <div className="space-y-3">
          <StarRating label="商品质量" value={quality} onChange={setQuality} />
          <StarRating label="物流速度" value={logistics} onChange={setLogistics} />
          <StarRating label="客服态度" value={service} onChange={setService} />
          <textarea
            className="w-full border rounded p-2 text-sm"
            rows={3}
            placeholder="图文评价（可选）"
            value={content}
            onChange={(e) => setContent(e.target.value)}
          />
          <div>
            <input
              type="file"
              accept=".jpg,.jpeg,.png"
              multiple
              onChange={(e) => onPickImages(e.target.files)}
              disabled={imageUrls.length >= MAX_IMAGES}
            />
            <div className="mt-1 flex flex-wrap gap-1">
              {imageUrls.map((u, i) => (
                <div key={i} className="relative">
                  <img src={u} alt="" className="h-12 w-12 rounded object-cover" />
                  <button
                    type="button"
                    className="absolute -right-1 -top-1 rounded-full bg-red-600 px-1 text-xs text-white"
                    onClick={() => setImageUrls((p) => p.filter((_, j) => j !== i))}
                  >×</button>
                </div>
              ))}
            </div>
          </div>
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={anonymous} onChange={(e) => setAnonymous(e.target.checked)} />
            匿名评价
          </label>
        </div>
        {err && <p className="mt-2 text-sm text-red-600">{err}</p>}
        <div className="mt-4 flex justify-end gap-2">
          <button onClick={onClose} className="rounded border px-3 py-1.5 text-sm">取消</button>
          <button
            onClick={submit}
            disabled={busy}
            className="rounded bg-brand-600 px-3 py-1.5 text-sm text-white disabled:opacity-50"
          >
            {busy ? '提交中…' : '提交评价'}
          </button>
        </div>
      </div>
    </div>
  )
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
npm test -- --run tests/ReviewModal.test.tsx
```
Expected: PASS (all 3 tests green). (The star-count assertion `>=15` accounts for 3×5 star buttons; adjust the exact matcher if the test counts differently — the intent is "3 rows of 5 stars render".)

- [ ] **Step 6: Commit**

```bash
git add frame-frontend/src/components/StarRating.tsx \
        frame-frontend/src/components/ReviewModal.tsx \
        frame-frontend/tests/ReviewModal.test.tsx
git commit -m "feat(phase-d): ReviewModal + StarRating — 3-D ratings, text, image upload, edit-after-submit

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 10: Wire `Orders.tsx` — active 未评价 tab + per-item 评价 button

**Files:**
- Modify: `frame-frontend/src/pages/Orders.tsx`
- Modify: `frame-frontend/tests/Orders.test.tsx`

**Interfaces:**
- Consumes: `@/api/review` (`listUnreviewedOrders`, `listOrderReviews`), `@/components/ReviewModal`.
- Produces: an active 未评价 tab (fetches `listUnreviewedOrders`) + a 写评价/编辑评价 button on each `OrderItem` row in the drawer (for DELIVERED/COMPLETED orders) that opens `<ReviewModal>`.

- [ ] **Step 1: Extend the Orders test for the 未评价 tab + review button**

In `frame-frontend/tests/Orders.test.tsx`, add `listUnreviewedOrders` and `listOrderReviews` to the existing `vi.mock('@/api/order', ...)` is NOT where they live — they're in `@/api/review`. Add a second mock:

```tsx
vi.mock('@/api/review', () => ({
  listUnreviewedOrders: vi.fn(),
  listOrderReviews: vi.fn(),
  createReview: vi.fn(),
  updateReview: vi.fn(),
  uploadReviewImage: vi.fn(),
}))
import { listUnreviewedOrders, listOrderReviews } from '@/api/review'
```

Then add tests (mirror the existing `order()`/`item()` helpers + `beforeEach` mock-return setup):

```tsx
it('未评价 tab fetches listUnreviewedOrders', async () => {
  ;(getMyOrders as any).mockResolvedValue(page([]))
  ;(listUnreviewedOrders as any).mockResolvedValue([])
  render(<MemoryRouter><CartProvider><Orders /></CartProvider></MemoryRouter>)
  fireEvent.click(screen.getByText('未评价'))
  await waitFor(() => expect(listUnreviewedOrders).toHaveBeenCalled())
})

it('drawer shows 写评价 button on a DELIVERED order item', async () => {
  const o = order({ id: 5, status: 'DELIVERED' })
  ;(getMyOrders as any).mockResolvedValue(page([o]))
  ;(getOrderItems as any).mockResolvedValue([item({ orderId: 5, productId: 100 })])
  ;(listOrderReviews as any).mockResolvedValue([])
  render(<MemoryRouter><CartProvider><Orders /></CartProvider></MemoryRouter>)
  fireEvent.click(screen.getByText('#5'))
  await waitFor(() => expect(screen.getByText('写评价')).toBeInTheDocument())
})
```

(Adapt the `#5` expand-toggle selector to match how the existing tests open the drawer — read the existing tests' expand interactions first.)

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frame-frontend && npm test -- --run tests/Orders.test.tsx
```
Expected: FAIL — 未评价 tab is still disabled; no 写评价 button renders.

- [ ] **Step 3: Make the 未评价 tab active + wire the drawer button**

In `Orders.tsx`:

a) Update the imports (add review API + ReviewModal + Review type):
```tsx
import { listUnreviewedOrders, listOrderReviews } from '@/api/review'
import ReviewModal from '@/components/ReviewModal'
import type { Review } from '@/api/types'
```

b) Change the TABS const: replace `{ label: '未评价 (Phase D)', disabled: true }` with `{ label: '未评价', unreviewed: true }` (and widen the type):
```tsx
const TABS: { label: string; status?: OrderStatus; unreviewed?: boolean }[] = [
  { label: '全部' },
  { label: '未付款', status: 'PENDING' },
  { label: '未发货', status: 'PAID' },
  { label: '未签收', status: 'SHIPPED' },
  { label: '已完成', status: 'COMPLETED' },
  { label: '未评价', unreviewed: true },
]
```

c) Add state for the unreviewed mode + review modal + per-order review cache:
```tsx
const [unreviewedMode, setUnreviewedMode] = useState(false)
const [reviewsByOrder, setReviewsByOrder] = useState<Record<number, Review[]>>({})
const [reviewTarget, setReviewTarget] = useState<{ productId: number; existing: Review | null } | null>(null)
```

d) In `load()`, branch on `unreviewedMode`:
```tsx
async function load() {
  setLoading(true); setError(null)
  try {
    if (unreviewedMode) {
      const list = await listUnreviewedOrders()
      setRows(list); setTotal(list.length)
    } else {
      const res = await getMyOrders(1, 50, activeStatus)
      setRows(res.records); setTotal(res.total)
    }
  } catch (err) {
    setError(err instanceof Error ? err.message : 'failed to load orders')
  } finally {
    setLoading(false)
  }
}
```
Add `unreviewedMode` to the `useEffect(load, [...])` deps (alongside `activeStatus`).

e) In the tab `onClick`: `onClick={() => { setActiveStatus(tab.status); setUnreviewedMode(!!tab.unreviewed) }}`. And the `active` calc: `const active = unreviewedMode ? !!tab.unreviewed : tab.status === activeStatus`. Remove the `disabled` logic (the 未评价 tab is no longer disabled).

f) In `toggleExpand(id)` (the existing drawer-open function), when expanding, also fetch reviews: `listOrderReviews(id).then(r => setReviewsByOrder(prev => ({ ...prev, [id]: r }))).catch(() => {})`.

g) In the drawer's `items.map((it) => ...)`, add a 评价 button for DELIVERED/COMPLETED orders:
```tsx
{(o.status === 'DELIVERED' || o.status === 'COMPLETED') && (
  <button
    onClick={() => setReviewTarget({
      productId: it.productId,
      existing: (reviewsByOrder[o.id] ?? []).find(r => r.productId === it.productId) ?? null,
    })}
    className="text-xs text-brand-600 hover:underline"
  >
    {(reviewsByOrder[o.id] ?? []).some(r => r.productId === it.productId) ? '编辑评价' : '写评价'}
  </button>
)}
```

h) Render the modal at the end of the component (when `reviewTarget` is set), passing `orderId={expandedId!}` (the currently expanded order) + `productId={reviewTarget.productId}` + `existing={reviewTarget.existing}`, with `onClose={() => setReviewTarget(null)}` and `onSuccess` that re-fetches reviews + reloads the list:
```tsx
{reviewTarget && expandedId && (
  <ReviewModal
    orderId={expandedId}
    productId={reviewTarget.productId}
    existing={reviewTarget.existing}
    onClose={() => setReviewTarget(null)}
    onSuccess={async () => {
      setReviewTarget(null)
      if (expandedId) {
        listOrderReviews(expandedId).then(r => setReviewsByOrder(p => ({ ...p, [expandedId]: r }))).catch(() => {})
      }
      await load()
    }}
  />
)}
```

- [ ] **Step 4: Run the Orders test to verify it passes**

```bash
npm test -- --run tests/Orders.test.tsx
```
Expected: PASS (existing Phase C tests still green + the 2 new tests green). If an existing test asserted the disabled 未评价 tab text `'未评价 (Phase D)'`, update it to the new label `'未评价'`.

- [ ] **Step 5: Commit**

```bash
git add frame-frontend/src/pages/Orders.tsx frame-frontend/tests/Orders.test.tsx
git commit -m "feat(phase-d): Orders.tsx — active 未评价 tab + per-item 写评价/编辑评价 button → ReviewModal

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 11: Fill Phase B sort gap — `sales|asc` + `rating|asc` in `Products.tsx`

**Files:**
- Modify: `frame-frontend/src/pages/Products.tsx:60-66` (the sort `<select>`).
- Modify: `frame-frontend/tests/Products.test.tsx`.

**Interfaces:**
- Consumes: nothing new (backend already supports all six combos).
- Produces: the two missing sort options surfaced in the UI.

- [ ] **Step 1: Extend the Products test for the two new options**

Read the existing `tests/Products.test.tsx` to match its render + assertion style, then add:

```tsx
it('sort dropdown exposes all 6 options incl sales|asc + rating|asc', () => {
  // ...render Products (matching existing test setup)...
  const select = screen.getByLabelText('排序') as HTMLSelectElement
  const values = Array.from(select.options).map(o => o.value)
  expect(values).toEqual(expect.arrayContaining([
    '', 'price|asc', 'price|desc', 'sales|desc', 'sales|asc', 'rating|desc', 'rating|asc',
  ]))
})
```

(Adapt the render boilerplate to match the existing test — it likely mocks `@/api/product` and `@/api/types`. Read it first.)

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frame-frontend && npm test -- --run tests/Products.test.tsx
```
Expected: FAIL — `sales|asc` and `rating|asc` options are absent.

- [ ] **Step 3: Add the two options to `Products.tsx`**

In the sort `<select>` (lines 60–66), after `销量降序` add `销量升序`, and after `评价降序` add `评价升序`:

```tsx
        <select aria-label="排序" className="border p-2 rounded" onChange={onSort}>
          <option value="">默认</option>
          <option value="price|asc">价格升序</option>
          <option value="price|desc">价格降序</option>
          <option value="sales|desc">销量降序</option>
          <option value="sales|asc">销量升序</option>
          <option value="rating|desc">评价降序</option>
          <option value="rating|asc">评价升序</option>
        </select>
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
npm test -- --run tests/Products.test.tsx
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frame-frontend/src/pages/Products.tsx frame-frontend/tests/Products.test.tsx
git commit -m "feat(phase-d): fill Phase B sort gap — add sales|asc + rating|asc options

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 12: E2E review lifecycle + final review + merge

**Files:**
- Create: `e2e/tests/reviews.test.mjs`
- Modify: `docs/superpowers/skipped-issues.md` (only if a hard bug is hit + skipped).
- Modify: `docs/superpowers/PHASE-D-STATUS.md` (new — final status report).
- Modify memory `frame-shop-uc-fulfillment.md` (Phase D done + Phase E resume point).

**Interfaces:**
- Consumes: the running local backend + all Phase D endpoints + an existing e2e auth/register helper (the catalog/orders e2e suites already register a user — reuse that helper; if the pre-existing auth SMS-gated cascade blocks registration in e2e, seed the SMS code in Redis the way `CheckoutFlowIntegrationTest` does).

- [ ] **Step 1: Read the existing e2e harness**

```bash
ls e2e/tests/ && sed -n '1,60p' e2e/tests/orders.test.mjs
```
Note: the base URL (likely `http://localhost:8081`), the register/login helper, the auth-token header, and how it drives checkout→pay→ship→confirm-receipt.

- [ ] **Step 2: Write the e2e test**

`e2e/tests/reviews.test.mjs` — drive the full flow as user+admin:
1. Register a user (seed SMS code in Redis, mirroring the integration test) + log in → token.
2. Admin-login (or reuse an admin fixture) → token.
3. Checkout 1 unit of a product → poll until PENDING → pay → admin ship (with tracking) → user confirm-receipt → order is DELIVERED.
4. `GET /orders/unreviewed` → assert the order id is present.
5. `POST /orders/{id}/reviews` (5/5/5, no images) for the order's item → assert 200 + the order is now COMPLETED (`GET /orders/{id}`).
6. `GET /orders/unreviewed` → assert the order id is absent.
7. `PUT /reviews/{reviewId}` (edit to 4/4/4 + content "edited") → assert 200 + `edited == true`.
8. (Rating-sort) `GET /products?sortField=rating&sortOrder=desc` → assert the reviewed product appears with `avgRating > 0`.

Use the same `fetch`/assertion style as `orders.test.mjs`. If steps 1–3 hit the pre-existing auth SMS-gated e2e cascade (deferred to Phase F per the Phase C status), seed the SMS code via a direct Redis call or a test-only register bypass the existing suites already use; if no bypass exists, mark the registration-dependent assertions `test.skip` with a reason in `skipped-issues.md` and keep the steps that an already-authed fixture can exercise.

- [ ] **Step 3: Run the e2e test**

```bash
cd e2e && npm test -- --run tests/reviews.test.mjs
```
Expected: PASS (or the skipped-issues path documented). Backend + frontend must be running locally on their dev ports.

- [ ] **Step 4: Re-run the full regression gate**

```bash
cd frame-backend && mvn -q test
cd ../frame-frontend && npm test -- --run
cd ../e2e && npm test -- --run   # all e2e suites
```
Expected: backend all green (104 + new review tests), frontend all green (38 + new tests), e2e catalog 7 + orders 11 + reviews new all green. Pre-existing non-catalog auth e2e failures remain out of scope.

- [ ] **Step 5: Final whole-branch review**

Read every changed file on the branch:
```bash
git diff main...feature/phase-d-reviews --stat
git diff main...feature/phase-d-reviews
```
Verify against the spec §7 error-handling table: ownership-before-state (NOT_FOUND on mismatch), state ∈ {DELIVERED, COMPLETED} else CONFLICT, productId-in-order else PARAM_INVALID, upsert-not-409 on duplicate, once-per-order COMPLETED+coin gating, image validation (oversize/bad-type/bad-ext → 400/PARAM_INVALID), edit-after-submit sets editedAt, no scheduler change. Note any non-blocking observations in the status doc. If a must-fix is found, fix it + re-run the affected suite before merge.

- [ ] **Step 6: Merge to main**

```bash
git checkout main
git merge --no-ff feature/phase-d-reviews -m "merge: Phase D (Reviews) — UC010

per-item 3-D star reviews + text + jpg/png images + edit-after-submit;
review-triggered DELIVERED→COMPLETED (once-per-order coin-stub seam);
未评价 tab; fills Phase B sort gap (sales|asc + rating|asc).

Co-Authored-By: Claude <noreply@anthropic.com>"
```

- [ ] **Step 7: Re-run regression on main + write the status doc + update memory**

```bash
cd frame-backend && mvn -q test && cd ../frame-frontend && npm test -- --run
```

Write `docs/superpowers/PHASE-D-STATUS.md` (mirror the Phase C status structure: what was built, tests-green counts, final-review verdict, deferred-to-Phase-F items, recovery map). Update memory `frame-shop-uc-fulfillment.md` with Phase D completion + the Phase E (coins, UC012) resume point.

- [ ] **Step 8: Commit the docs + memory**

```bash
git add docs/superpowers/PHASE-D-STATUS.md "C:/Users/tcmao/.claude/projects/C--Users-tcmao-Desktop-newRepo/memory/frame-shop-uc-fulfillment.md"
git commit -m "docs(phase-d): Phase D status report + memory update

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Self-Review

**1. Spec coverage** — checked each spec section against the tasks:
- §1 scope: per-item reviews (Task 1 unique-key migration + Task 4 create), 3-D stars (Task 2 DTOs + Task 4), text + images (Task 2/5), edit-after-submit (Task 4 update + Task 9 modal prefill), COMPLETED trigger (Task 4), 未评价 tab (Task 6 query + Task 10 UI), Phase B sort gap (Task 11), coin stub (Task 3). ✓
- §2 decisions: all eight decisions map to a task. ✓
- §3 data model: Task 0 (migration + columns + multipart limits). ✓
- §4 backend: Tasks 1–6 (entity, mapper, DTOs, stub, service, image service, controller, unreviewed query, security). ✓
- §5 frontend: Tasks 8–11 (types, api client, StarRating/ReviewModal, Orders wiring, Products sort). ✓
- §6 testing: unit (Task 4), integration (Tasks 1, 6, 7), frontend (Tasks 9, 10, 11), e2e (Task 12). ✓
- §7 error handling: covered by Task 4 unit tests (state, ownership, productId, upsert, COMPLETED-with-unreviewed) + Task 5 image-validation tests + Task 5 review-integration edit test. ✓
- §8 build order: tasks 0→12 follow it. ✓

**2. Placeholder scan** — no TBD/TODO/"add error handling"/"similar to Task N" in the plan body. Every code step has real code. (The single intentional TODO is inside the `CoinServiceStub` source string — that's a documented Phase-E seam, not a plan gap.) ✓

**3. Type consistency** — `ReviewService.create(req, userId)`/`update(reviewId, req, userId)`/`listByOrder(orderId, userId)` match across Task 4 interface, impl, and Task 6 controller. `ReviewMapper.selectByOrderAndProduct(orderId, productId)`/`selectByOrderId(orderId)` match Task 1 + Task 4. `ReviewVO.isEdited()`/`productName` match Task 2 + frontend `Review.edited` (Task 8). `OrderService.listUnreviewedOrders(userId)` matches Task 6 interface + impl + controller + frontend `listUnreviewedOrders()`. `ReviewImageService.upload(file)` matches Task 5 + Task 6. ✓

No gaps or inconsistencies found.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-20-phase-d-reviews.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
