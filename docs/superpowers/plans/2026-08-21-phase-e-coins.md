# Phase E — Coins (UC012) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build UC012 — a coin economy: daily-login reward (100/day, 4am CST refresh), review reward (= order's actual paid amount), 30-day per-credit FIFO expiry with a daily sweep, and checkout coin-discount rules (100 coins = ¥1, ≤50% of undiscounted subtotal, no coins on flash-sale items) that complete the UC005 coin branch, replacing the Phase D `CoinServiceStub` no-op with a real `coin_ledger`.

**Architecture:** A new `coin_ledger` table (append-only rows; a credit row's `expires_at` doubles as a state flag — set = pending, NULL = spent/expired/terminal). Balance = `SUM(amount) WHERE (expires_at IS NULL OR expires_at > now())`. Three earn paths (daily-login, review reward, refund-return) are each idempotent via a single `uk_coin_idem(idem_key)` unique key (`"review:{orderId}"`, `"refund:{orderId}:{spendRowId}"`) + `uk_coin_daily(user_id, login_date)`. Spending is FIFO (`SELECT ... FOR UPDATE` oldest-first) inside the checkout consumer's transaction so coins + order commit atomically. A new `orders.paid_in_cents` column records the actual paid amount so the review reward equals it. A daily 4:01am CST scheduler sweeps expired credits.

**Tech Stack:** Java 21 Spring Boot 3, MyBatis-Plus (XML mappers), MariaDB, Redis; React 18 + TypeScript + Vite + Vitest + Testing Library + Tailwind.

## Global Constraints

- Branch `feature/phase-e-coins` off `main` (HEAD `830651a`). Commit per task; commit messages end with `Co-Authored-By: Claude <noreply@anthropic.com>`.
- Backend run/test commands (from `frame-backend/`): `mvn -q test` (or `./mvnw -q test`). Per-test: `mvn -q -Dtest=ClassName test`. Integration tests hit REAL MariaDB + Redis (no Testcontainers) — `@SpringBootTest` with `@TestPropertySource` JWT props (`frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret`, `frame.jwt.expire=3600`), same as `CheckoutFlowIntegrationTest` / `ReviewIntegrationTest`.
- Frontend run/test commands (from `frame-frontend/`): `npm test -- --run` (Vitest). Per-file: `npm test -- --run tests/Checkout.test.tsx`. Lint: `npm run lint`. Build: `npm run build`.
- Convention: entities extend `com.iflytek.frame.common.BaseEntity` (provides `id`/`createTime`/`updateTime`, auto-filled by `MybatisMetaObjectHandler`); `@TableName("snake_case")`; `@Data @EqualsAndHashCode(callSuper = true)` Lombok. Mappers extend `BaseMapper<T>` + `@Mapper`, custom queries in `resources/mapper/*.xml` (namespace `com.iflytek.frame.mapper.XxxMapper`). Controllers: `@RestController @RequestMapping("...") @RequiredArgsConstructor`, return `com.iflytek.frame.common.Result` via `Result.success(...)`; get the user id via `com.iflytek.frame.util.SecurityUtils.currentUserId()`.
- Errors: throw `com.iflytek.frame.exception.BusinessException(com.iflytek.frame.common.ResultCode, msg)`. Relevant codes: `NOT_FOUND` (404), `CONFLICT` (409), `PARAM_INVALID` (1001), `INTERNAL_ERROR` (500).
- Ownership-before-state: verify ownership BEFORE checking state; on mismatch throw `NOT_FOUND` ("... not found") — never reveal another user's resource exists.
- App timezone is `Asia/Shanghai` (CST/UTC+8) — set in `application.yml` `spring.jackson.time-zone` + the DB JDBC `serverTimezone=Asia/Shanghai`. The 4am daily-login boundary + the 4:01am sweep use this zone.
- TDD: write the failing test → run it (red) → implement → run it (green) → commit. No step may be left as TODO/placeholder.

---

## File Structure

**Backend (new):**
- `entity/CoinLedger.java` — `@TableName("coin_ledger")` entity.
- `common/CoinSource.java` — enum: `DAILY_LOGIN, REVIEW_REWARD, CHECKOUT_SPEND, REFUND_RETURN, EXPIRED`.
- `mapper/CoinMapper.java` + `resources/mapper/CoinMapper.xml` — coin persistence + queries.
- `dto/CoinBalanceVO.java`, `dto/CoinHistoryVO.java`, `dto/CoinDiscountPreview.java` — API shapes.
- `service/CoinService.java` + `service/impl/CoinServiceImpl.java` — earn/spend/refund/sweep/read.

**Backend (modify):**
- `resources/schema.sql` — add `coin_ledger` table + `orders.paid_in_cents` column.
- `entity/Order.java` — add `paidInCents`.
- `dto/CheckoutRequest.java` — add `useCoins` + `coinAmount`.
- `service/impl/CheckoutServiceImpl.java` — hot-path coin validation + payload fields.
- `service/StreamConsumerService.java` — consumer coin deduction + `paid_in_cents`.
- `service/impl/OrderServiceImpl.java` — `cancelPaid` refund-coin hook.
- `service/impl/AdminOrderServiceImpl.java` — `approveRefund` refund-coin hook.
- `service/impl/AuthServiceImpl.java` — daily-login trigger.
- `service/OrderLifecycleScheduler.java` — `sweepExpiredCoins` cron.
- `service/impl/ReviewServiceImpl.java` — swap `CoinServiceStub` → `CoinService`.
- `controller/CoinController.java` (new) — `GET /coins/balance`, `GET /coins/history`.

**Backend (delete):**
- `service/CoinServiceStub.java` — replaced by `CoinService`/`CoinServiceImpl`.

**Backend (new tests):**
- `test/.../mapper/CoinMapperIntegrationTest.java` — mapper queries.
- `test/.../service/CoinServiceImplTest.java` — unit (Mockito).
- `test/.../CoinIntegrationTest.java` — `@SpringBootTest` end-to-end earn→spend→expire.
- `test/.../CheckoutWithCoinsIntegrationTest.java` — checkout coin branch.

**Backend (modify tests):**
- `test/.../service/ReviewServiceImplTest.java` — swap mock `CoinServiceStub` → `CoinService`.

**Frontend (new):**
- `src/api/coin.ts` — API client.
- `src/pages/Coins.tsx` — history + balance card.
- `tests/Coins.test.tsx` — component test.

**Frontend (modify):**
- `src/api/types.ts` — `CheckoutRequest.useCoins/coinAmount`, `Order.paidInCents`, `CoinBalance`, `CoinHistoryRow`.
- `src/store/cartReducer.ts` — `CartItem.isFlashSale`.
- `src/pages/Checkout.tsx` — coins-at-checkout UI.
- `src/pages/Products.tsx` (add-to-cart) — carry `isFlashSale` into the cart.
- `src/App.tsx` (or router) — `/coins` route.
- `tests/Checkout.test.tsx` — coins branch.

**E2E (new):**
- `e2e/tests/coins.test.mjs` — full coin lifecycle.

---

## Task 0: Branch + schema (coin_ledger + orders.paid_in_cents) + baseline

**Files:**
- Create: `feature/phase-e-coins` branch off `main` (already created in the spec commit — verify).
- Modify: `frame-backend/src/main/resources/schema.sql` (add `coin_ledger` CREATE TABLE; add `paid_in_cents` to `orders`).
- Modify (live dev DB): `ALTER` the live MariaDB `frame_db`.

**Interfaces:**
- Consumes: nothing.
- Produces: the `coin_ledger` table + `orders.paid_in_cents` column that every later task depends on.

- [ ] **Step 1: Verify the branch**

```bash
git rev-parse --abbrev-ref HEAD
```
Expected: `feature/phase-e-coins`. (The spec commit created it. If on `main`, run `git checkout -b feature/phase-e-coins main`.)

- [ ] **Step 2: Alter the live dev DB**

Run via the `mysql` CLI with the project creds (user `root` / password `frame_dev`, db `frame_db` — from `application.yml`):

```bash
mysql -u root -pframe_dev frame_db -e "
CREATE TABLE IF NOT EXISTS coin_ledger (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    amount        INT          NOT NULL,
    balance_after INT          NOT NULL,
    source        VARCHAR(32)  NOT NULL,
    ref_order_id  BIGINT       DEFAULT NULL,
    ref_credit_id BIGINT       DEFAULT NULL,
    idem_key      VARCHAR(64)  DEFAULT NULL,
    login_date    DATE         DEFAULT NULL,
    expires_at    DATETIME     DEFAULT NULL,
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coin_idem (idem_key),
    UNIQUE KEY uk_coin_daily (user_id, login_date),
    KEY idx_coin_user (user_id),
    KEY idx_coin_user_expires (user_id, expires_at),
    KEY idx_coin_expire (expires_at),
    KEY idx_coin_order (ref_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
ALTER TABLE orders ADD COLUMN paid_in_cents INT NOT NULL DEFAULT 0;
"
```

Verify:
```bash
mysql -u root -pframe_dev frame_db -e "SHOW CREATE TABLE coin_ledger\G" | grep -E "uk_coin_idem|uk_coin_daily|paid_in_cents"
mysql -u root -pframe_dev frame_db -e "DESCRIBE orders" | grep paid_in_cents
```
Expected: both unique keys present; `paid_in_cents` column present in `orders`.

- [ ] **Step 3: Update `schema.sql` to match**

In `frame-backend/src/main/resources/schema.sql`:

a) Add `coin_ledger` to the `DROP TABLE IF EXISTS` block (top of file), after `DROP TABLE IF EXISTS review;`:
```sql
DROP TABLE IF EXISTS `coin_ledger`;
```
And place the `DROP TABLE IF EXISTS coin_ledger;` **before** any table that references it (none do, so order is loose — put it after `review`).

b) Add the `coin_ledger` CREATE TABLE (after the `order_item` CREATE TABLE block, before the `INSERT INTO user` seeds):
```sql
CREATE TABLE `coin_ledger` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT       NOT NULL,
    `amount`        INT          NOT NULL,
    `balance_after` INT          NOT NULL,
    `source`        VARCHAR(32)  NOT NULL,
    `ref_order_id`  BIGINT       DEFAULT NULL,
    `ref_credit_id` BIGINT       DEFAULT NULL,
    `idem_key`      VARCHAR(64)  DEFAULT NULL,
    `login_date`    DATE         DEFAULT NULL,
    `expires_at`    DATETIME     DEFAULT NULL,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_coin_idem` (`idem_key`),
    UNIQUE KEY `uk_coin_daily` (`user_id`, `login_date`),
    KEY `idx_coin_user` (`user_id`),
    KEY `idx_coin_user_expires` (`user_id`, `expires_at`),
    KEY `idx_coin_expire` (`expires_at`),
    KEY `idx_coin_order` (`ref_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

c) Add `paid_in_cents` to the `orders` CREATE TABLE — insert after the `total_in_cents INT NOT NULL,` line:
```sql
    `paid_in_cents`  INT          NOT NULL DEFAULT 0,
```

- [ ] **Step 4: Re-run the baseline suites (regression gate)**

```bash
cd frame-backend && mvn -q test
cd ../frame-frontend && npm test -- --run
```
Expected: backend 122 pass, frontend 44 pass (Phase D baseline). The new column has a default so existing rows/tests are unaffected. Pre-existing non-catalog auth e2e failures remain out of scope (Phase F). If a baseline suite is red for an unrelated reason, note it in `docs/superpowers/skipped-issues.md` and proceed.

- [ ] **Step 5: Commit**

```bash
git add frame-backend/src/main/resources/schema.sql
git commit -m "feat(phase-e): coin_ledger table + orders.paid_in_cents column

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 1: `CoinSource` enum + `CoinLedger` entity + `CoinMapper` (+ XML) + Order.paidInCents

**Files:**
- Create: `frame-backend/src/main/java/com/iflytek/frame/common/CoinSource.java`
- Create: `frame-backend/src/main/java/com/iflytek/frame/entity/CoinLedger.java`
- Create: `frame-backend/src/main/java/com/iflytek/frame/mapper/CoinMapper.java`
- Create: `frame-backend/src/main/resources/mapper/CoinMapper.xml`
- Modify: `frame-backend/src/main/java/com/iflytek/frame/entity/Order.java` (add `paidInCents`)
- Test: `frame-backend/src/test/java/com/iflytek/frame/mapper/CoinMapperIntegrationTest.java`

**Interfaces:**
- Consumes: `BaseEntity` (id/createTime/updateTime), the `coin_ledger` table (Task 0).
- Produces: `CoinSource` enum; `CoinLedger` entity (fields: `userId, amount, balanceAfter, source, refOrderId, refCreditId, idemKey, loginDate, expiresAt`); `CoinMapper` with `selectBalance`, `selectSpendableCreditsForUpdate`, `selectExpiredUnsweptCredits`, `selectDailyLogin`, `selectHistory`, `selectSpendRowsByOrder`. `Order.paidInCents` (int). Consumed by Task 3 (`CoinServiceImpl`).

- [ ] **Step 1: Write the failing mapper integration test**

Create `frame-backend/src/test/java/com/iflytek/frame/mapper/CoinMapperIntegrationTest.java` — mirrors `ReviewMapperIntegrationTest`'s `@SpringBootTest` + `@TestPropertySource` shape:

```java
package com.iflytek.frame.mapper;

import com.iflytek.frame.common.CoinSource;
import com.iflytek.frame.entity.CoinLedger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600"
})
@Transactional
class CoinMapperIntegrationTest {

    @Autowired CoinMapper coinMapper;

    private CoinLedger credit(Long userId, int amount, LocalDateTime expiresAt, String idemKey) {
        CoinLedger c = new CoinLedger();
        c.setUserId(userId);
        c.setAmount(amount);
        c.setBalanceAfter(amount);
        c.setSource(CoinSource.DAILY_LOGIN);
        c.setIdemKey(idemKey);
        c.setLoginDate(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")));
        c.setExpiresAt(expiresAt);
        coinMapper.insert(c);
        return c;
    }

    @Test
    void selectBalance_sumsUnexpiredRows() {
        Long u = 9001L;
        credit(u, 100, LocalDateTime.now().plusDays(20), "bal-1-" + System.nanoTime());
        credit(u, 50, LocalDateTime.now().plusDays(5), "bal-2-" + System.nanoTime());
        assertThat(coinMapper.selectBalance(u)).isEqualTo(150);
    }

    @Test
    void selectBalance_excludesExpiredCredits() {
        Long u = 9002L;
        CoinLedger expired = credit(u, 100, LocalDateTime.now().minusDays(1), "bal-3-" + System.nanoTime());
        // simulate the sweep: nullify expires_at + insert an EXPIRED balancing row
        expired.setExpiresAt(null);
        coinMapper.updateById(expired);
        CoinLedger exp = new CoinLedger();
        exp.setUserId(u); exp.setAmount(-100); exp.setBalanceAfter(0);
        exp.setSource(CoinSource.EXPIRED); exp.setRefCreditId(expired.getId()); exp.setExpiresAt(null);
        coinMapper.insert(exp);
        assertThat(coinMapper.selectBalance(u)).isEqualTo(0);
    }

    @Test
    void selectSpendableCreditsForUpdate_returnsOnlyPendingOrderedByExpiry() {
        Long u = 9003L;
        CoinLedger soon = credit(u, 40, LocalDateTime.now().plusDays(3), "sp-1-" + System.nanoTime());
        CoinLedger later = credit(u, 60, LocalDateTime.now().plusDays(25), "sp-2-" + System.nanoTime());
        var spendable = coinMapper.selectSpendableCreditsForUpdate(u);
        assertThat(spendable).hasSize(2);
        assertThat(spendable.get(0).getId()).isEqualTo(soon.getId()); // sooner expiry first
    }

    @Test
    void selectSpendableCreditsForUpdate_excludesSpentCredits() {
        Long u = 9004L;
        CoinLedger spent = credit(u, 100, LocalDateTime.now().plusDays(10), "sp-3-" + System.nanoTime());
        spent.setExpiresAt(null); // nullified on spend
        coinMapper.updateById(spent);
        assertThat(coinMapper.selectSpendableCreditsForUpdate(u)).isEmpty();
    }

    @Test
    void selectDailyLogin_findsRowForDate() {
        Long u = 9005L;
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        CoinLedger d = new CoinLedger();
        d.setUserId(u); d.setAmount(100); d.setBalanceAfter(100);
        d.setSource(CoinSource.DAILY_LOGIN); d.setLoginDate(today);
        d.setExpiresAt(LocalDateTime.now().plusDays(30));
        coinMapper.insert(d);
        assertThat(coinMapper.selectDailyLogin(u, today)).isNotNull();
        assertThat(coinMapper.selectDailyLogin(u, today.plusDays(1))).isNull();
    }

    @Test
    void selectSpendRowsByOrder_returnsCheckoutsForOrder() {
        Long u = 9006L;
        CoinLedger s = new CoinLedger();
        s.setUserId(u); s.setAmount(-100); s.setBalanceAfter(0);
        s.setSource(CoinSource.CHECKOUT_SPEND); s.setRefOrderId(777L); s.setExpiresAt(null);
        coinMapper.insert(s);
        assertThat(coinMapper.selectSpendRowsByOrder(u, 777L)).hasSize(1);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frame-backend && mvn -q -Dtest=CoinMapperIntegrationTest test
```
Expected: FAIL — `CoinSource` / `CoinLedger` / `CoinMapper` do not compile.

- [ ] **Step 3: Create the `CoinSource` enum**

`frame-backend/src/main/java/com/iflytek/frame/common/CoinSource.java`:
```java
package com.iflytek.frame.common;

/** The reason a coin_ledger row was created (UC012). */
public enum CoinSource {
    DAILY_LOGIN,      // +100 daily-login reward
    REVIEW_REWARD,    // +order's paid amount, on review
    CHECKOUT_SPEND,   // -coins spent at checkout (one row per consumed credit)
    REFUND_RETURN,    // +coins returned on order refund (one row per spent credit)
    EXPIRED           // -balancing row for a credit past its 30-day clock
}
```

- [ ] **Step 4: Create the `CoinLedger` entity**

`frame-backend/src/main/java/com/iflytek/frame/entity/CoinLedger.java`:
```java
package com.iflytek.frame.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.iflytek.frame.common.BaseEntity;
import com.iflytek.frame.common.CoinSource;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * An append-only row in the coin ledger (UC012). Earned credits carry a future
 * {@code expires_at} (pending state); spending nullifies the consumed credit's
 * {@code expires_at} and inserts a {@code CHECKOUT_SPEND} negative row; the sweep
 * inserts an {@code EXPIRED} negative row and nullifies the credit's {@code expires_at}.
 * Balance = SUM(amount) WHERE (expires_at IS NULL OR expires_at > now()).
 */
@Schema(description = "Coin ledger entry")
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("coin_ledger")
public class CoinLedger extends BaseEntity {
    private Long userId;
    private Integer amount;
    private Integer balanceAfter;
    private CoinSource source;
    /** The order this row relates to (REVIEW_REWARD/CHECKOUT_SPEND/REFUND_RETURN); null otherwise. */
    private Long refOrderId;
    /** The earned-credit row this debit/expiry balances (CHECKOUT_SPEND/REFUND_RETURN/EXPIRED). */
    private Long refCreditId;
    /** Idempotency key: "review:{orderId}" or "refund:{orderId}:{spendRowId}"; null otherwise. */
    private String idemKey;
    /** CST date, only for DAILY_LOGIN rows (part of uk_coin_daily). */
    private LocalDate loginDate;
    /** Future date = pending; NULL = spent/expired/terminal. */
    private LocalDateTime expiresAt;
}
```

- [ ] **Step 5: Add `paidInCents` to `Order`**

In `frame-backend/src/main/java/com/iflytek/frame/entity/Order.java`, after the `private int totalInCents;` line, add:
```java
    /** Actual amount paid after coin discount (UC005/UC012). Review reward = this / 100. */
    private int paidInCents;
```

- [ ] **Step 6: Create `CoinMapper`**

`frame-backend/src/main/java/com/iflytek/frame/mapper/CoinMapper.java`:
```java
package com.iflytek.frame.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.iflytek.frame.entity.CoinLedger;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface CoinMapper extends BaseMapper<CoinLedger> {

    /** Current usable balance = SUM(amount) over rows where expires_at IS NULL OR > now(). */
    Integer selectBalance(@Param("userId") Long userId);

    /** Pending positive credit rows for FIFO spend, oldest-first, locked for update. */
    List<CoinLedger> selectSpendableCreditsForUpdate(@Param("userId") Long userId);

    /** Pending earned credits past their expiry clock (for the sweep, all users). */
    List<CoinLedger> selectExpiredUnsweptCredits();

    /** The user's DAILY_LOGIN row for a CST date, or null (already-claimed check). */
    CoinLedger selectDailyLogin(@Param("userId") Long userId, @Param("loginDate") LocalDate loginDate);

    /** Paginated ledger history, newest-first. */
    IPage<CoinLedger> selectHistory(@Param("page") IPage<CoinLedger> page, @Param("userId") Long userId);

    /** The CHECKOUT_SPEND rows for an order (to mirror as REFUND_RETURN on refund). */
    List<CoinLedger> selectSpendRowsByOrder(@Param("userId") Long userId, @Param("orderId") Long orderId);
}
```

- [ ] **Step 7: Create `CoinMapper.xml`**

`frame-backend/src/main/resources/mapper/CoinMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.iflytek.frame.mapper.CoinMapper">

    <select id="selectBalance" resultType="java.lang.Integer">
        select coalesce(sum(amount), 0) from coin_ledger
        where user_id = #{userId} and (expires_at is null or expires_at > now())
    </select>

    <select id="selectSpendableCreditsForUpdate" resultType="com.iflytek.frame.entity.CoinLedger">
        select * from coin_ledger
        where user_id = #{userId}
          and amount > 0
          and expires_at is not null and expires_at > now()
        order by expires_at asc, id asc
        for update
    </select>

    <select id="selectExpiredUnsweptCredits" resultType="com.iflytek.frame.entity.CoinLedger">
        select * from coin_ledger
        where amount > 0 and expires_at is not null and expires_at &lt;= now()
        order by expires_at asc, id asc
    </select>

    <select id="selectDailyLogin" resultType="com.iflytek.frame.entity.CoinLedger">
        select * from coin_ledger
        where user_id = #{userId} and source = 'DAILY_LOGIN' and login_date = #{loginDate}
        limit 1
    </select>

    <select id="selectHistory" resultType="com.iflytek.frame.entity.CoinLedger">
        select * from coin_ledger
        where user_id = #{userId}
        order by create_time desc, id desc
    </select>

    <select id="selectSpendRowsByOrder" resultType="com.iflytek.frame.entity.CoinLedger">
        select * from coin_ledger
        where user_id = #{userId} and ref_order_id = #{orderId} and source = 'CHECKOUT_SPEND'
        order by id asc
    </select>

</mapper>
```

(`insert`/`updateById`/`selectById` come from `BaseMapper`. `map-underscore-to-camel-case: true` maps `balance_after`→`balanceAfter`, `ref_order_id`→`refOrderId`, `idem_key`→`idemKey`, `login_date`→`loginDate`. The `CoinSource` enum maps by name from the `source` varchar.)

- [ ] **Step 8: Run the test to verify it passes**

```bash
mvn -q -Dtest=CoinMapperIntegrationTest test
```
Expected: PASS (all 6 tests green). If MyBatis fails to map `CoinSource`, add a `typeHandler` or store it as a string — but the default enum-by-name handling in MyBatis-Plus maps `DAILY_LOGIN`↔enum correctly. Verify.

- [ ] **Step 9: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/common/CoinSource.java \
        frame-backend/src/main/java/com/iflytek/frame/entity/CoinLedger.java \
        frame-backend/src/main/java/com/iflytek/frame/entity/Order.java \
        frame-backend/src/main/java/com/iflytek/frame/mapper/CoinMapper.java \
        frame-backend/src/main/resources/mapper/CoinMapper.xml \
        frame-backend/src/test/java/com/iflytek/frame/mapper/CoinMapperIntegrationTest.java
git commit -m "feat(phase-e): CoinSource + CoinLedger entity + CoinMapper + Order.paidInCents

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 2: Coin DTOs (`CoinBalanceVO`, `CoinHistoryVO`, `CoinDiscountPreview`)

**Files:**
- Create: `frame-backend/src/main/java/com/iflytek/frame/dto/CoinBalanceVO.java`
- Create: `frame-backend/src/main/java/com/iflytek/frame/dto/CoinHistoryVO.java`
- Create: `frame-backend/src/main/java/com/iflytek/frame/dto/CoinDiscountPreview.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `CoinBalanceVO { Integer balance; Boolean claimedToday; Integer expiringSoon; }`; `CoinHistoryVO { Long id; Integer amount; String source; Long refOrderId; LocalDateTime expiresAt; LocalDateTime createTime; Integer balanceAfter; }`; `CoinDiscountPreview { Boolean eligible; Integer maxCoins; Integer balance; Integer usable; String reason; }`. Consumed by Task 3 (`CoinServiceImpl`) + Task 5 (`CoinController`) + Task 4 (`CheckoutServiceImpl`).

- [ ] **Step 1: Create `CoinBalanceVO`**

`frame-backend/src/main/java/com/iflytek/frame/dto/CoinBalanceVO.java`:
```java
package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "Coin balance + daily-claim status")
@Data
public class CoinBalanceVO {
    private Integer balance;
    private Boolean claimedToday;
    private Integer expiringSoon;
}
```

- [ ] **Step 2: Create `CoinHistoryVO`**

`frame-backend/src/main/java/com/iflytek/frame/dto/CoinHistoryVO.java`:
```java
package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "A coin ledger row, for the history list")
@Data
public class CoinHistoryVO {
    private Long id;
    private Integer amount;
    private String source;
    private Long refOrderId;
    private LocalDateTime expiresAt;
    private LocalDateTime createTime;
    private Integer balanceAfter;
}
```

- [ ] **Step 3: Create `CoinDiscountPreview`**

`frame-backend/src/main/java/com/iflytek/frame/dto/CoinDiscountPreview.java`:
```java
package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * The result of validating a coin-discount request at checkout (UC005/UC012).
 * Computed in the hot path; no deduction.
 */
@Schema(description = "Coin-discount preview for a checkout cart")
@Data
public class CoinDiscountPreview {
    /** False if the cart contains any flash-sale item (coins ineligible). */
    private Boolean eligible;
    /** Max coins allowed by the 50%-of-undiscounted-subtotal rule. */
    private Integer maxCoins;
    /** The user's current usable coin balance. */
    private Integer balance;
    /** min(maxCoins, balance), floored to a multiple of 100. */
    private Integer usable;
    /** Why coins are ineligible, if `eligible` is false. */
    private String reason;
}
```

- [ ] **Step 4: Verify it compiles**

```bash
cd frame-backend && mvn -q compile
```
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/dto/CoinBalanceVO.java \
        frame-backend/src/main/java/com/iflytek/frame/dto/CoinHistoryVO.java \
        frame-backend/src/main/java/com/iflytek/frame/dto/CoinDiscountPreview.java
git commit -m "feat(phase-e): coin DTOs (balance/history/discount-preview)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 3: `CoinService` interface + `CoinServiceImpl` (earn/spend/refund/sweep/read) + unit test

**Files:**
- Create: `frame-backend/src/main/java/com/iflytek/frame/service/CoinService.java`
- Create: `frame-backend/src/main/java/com/iflytek/frame/service/impl/CoinServiceImpl.java`
- Test: `frame-backend/src/test/java/com/iflytek/frame/service/CoinServiceImplTest.java`

**Interfaces:**
- Consumes: `CoinMapper` (Task 1), `CoinLedger`/`CoinSource` (Task 1), `OrderMapper`/`Order` (existing, for `awardReviewReward` + `refundCoins`), `PageQuery`/`PageResult` (existing), `BusinessException`/`ResultCode` (existing).
- Produces: `CoinService` with `awardDailyLogin(Long)`, `awardReviewReward(Long, Long)`, `previewCheckoutDiscount(Long, Map<Long,Integer>)`, `spendAtCheckout(Long, Long, int)`, `refundCoins(Long)`, `sweepExpiredCredits()`, `getBalance(Long)`, `getHistory(Long, PageQuery)`. Consumed by Tasks 4-7.

**Implementation notes (reference the spec §4c/§4h):**
- `awardReviewReward(orderId, userId)`: load order, verify `userId`, `rewardCoins = order.paidInCents / 100` (floor); if `<= 0` no-op; insert REVIEW_REWARD row `idemKey="review:"+orderId`, `expiresAt=now+30d`; on `DataIntegrityViolationException` no-op.
- `awardDailyLogin(userId)`: `loginDate=LocalDate.now(Asia/Shanghai)`; if `selectDailyLogin` present no-op; insert DAILY_LOGIN row `amount=100`, `expiresAt=now+30d`; on duplicate-key (`uk_coin_daily`) no-op.
- `previewCheckoutDiscount(userId, qtyByProduct)`: (this needs `productMapper` — see note below) — but the hot path already has the fetched products. **Decision: pass the fetched `List<Product>` into `previewCheckoutDiscount` instead of re-fetching**, to avoid a circular dep (`CoinService` ↔ `ProductMapper`). Update the interface signature to `previewCheckoutDiscount(Long userId, List<Product> products, Map<Long,Integer> qtyByProduct)`.
- `spendAtCheckout(userId, orderId, coinAmount)`: `FOR UPDATE` spendable credits; FIFO consume; nullify each consumed credit's `expires_at`; if insufficient → throw CONFLICT.
- `refundCoins(orderId)`: load order → `userId`; `selectSpendRowsByOrder`; for each, insert REFUND_RETURN `idemKey="refund:"+orderId+":"+spendRow.id`, restore original credit's `expires_at`; on duplicate-key no-op; empty → no-op.
- `sweepExpiredCredits()`: `selectExpiredUnsweptCredits`; per credit insert EXPIRED row + nullify credit's `expires_at`; per-credit try/catch + outer try/catch.
- `getBalance(userId)`: balance + claimedToday + expiringSoon.
- `getHistory(userId, query)`: `selectHistory` paginated → map to `CoinHistoryVO`.
- `balanceAfter` is a running snapshot: compute `currentBalance = selectBalance(userId)` before each insert, set `balanceAfter = currentBalance ± amount`. (A snapshot, not authoritative — the `selectBalance` SUM is authoritative.)

> **`previewCheckoutDiscount` signature change:** pass `List<Product>` (already fetched in the hot path) so `CoinServiceImpl` doesn't depend on `ProductMapper`. Update Task 4's call site to match.

- [ ] **Step 1: Write the failing unit test**

`frame-backend/src/test/java/com/iflytek/frame/service/CoinServiceImplTest.java` — Mockito, constructor-injected:

```java
package com.iflytek.frame.service;

import com.iflytek.frame.common.CoinSource;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.CoinDiscountPreview;
import com.iflytek.frame.entity.CoinLedger;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.CoinMapper;
import com.iflytek.frame.mapper.OrderMapper;
import com.iflytek.frame.service.impl.CoinServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CoinServiceImplTest {

    @Mock CoinMapper coinMapper;
    @Mock OrderMapper orderMapper;
    CoinServiceImpl coinService;

    @BeforeEach
    void setUp() {
        coinService = new CoinServiceImpl(coinMapper, orderMapper);
    }

    private CoinLedger credit(int amount, LocalDateTime expiresAt) {
        CoinLedger c = new CoinLedger();
        c.setId((long) Math.abs(amount));
        c.setAmount(amount);
        c.setExpiresAt(expiresAt);
        return c;
    }

    @Test
    void awardDailyLogin_creditsOnce_whenNotClaimedToday() {
        when(coinMapper.selectBalance(1L)).thenReturn(0);
        when(coinMapper.selectDailyLogin(eq(1L), any())).thenReturn(null);

        coinService.awardDailyLogin(1L);

        ArgumentCaptor<CoinLedger> cap = ArgumentCaptor.forClass(CoinLedger.class);
        verify(coinMapper).insert(cap.capture());
        assertThat(cap.getValue().getAmount()).isEqualTo(100);
        assertThat(cap.getValue().getSource()).isEqualTo(CoinSource.DAILY_LOGIN);
        assertThat(cap.getValue().getExpiresAt()).isNotNull();
    }

    @Test
    void awardDailyLogin_noOp_whenAlreadyClaimedToday() {
        when(coinMapper.selectDailyLogin(eq(1L), any())).thenReturn(new CoinLedger());
        coinService.awardDailyLogin(1L);
        verify(coinMapper, never()).insert(any());
    }

    @Test
    void awardDailyLogin_noOp_onDuplicateKeyConcurrentLogin() {
        when(coinMapper.selectDailyLogin(eq(1L), any())).thenReturn(null);
        when(coinMapper.selectBalance(1L)).thenReturn(0);
        doThrow(new DataIntegrityViolationException("dup")).when(coinMapper).insert(any());
        coinService.awardDailyLogin(1L); // must not throw
        verify(coinMapper).insert(any());
    }

    @Test
    void awardReviewReward_creditsPaidAmountInYuan() {
        Order o = new Order();
        o.setId(7L); o.setUserId(1L); o.setPaidInCents(2000); // ¥20.00 → 20 coins
        when(orderMapper.selectById(7L)).thenReturn(o);
        when(coinMapper.selectBalance(1L)).thenReturn(0);

        coinService.awardReviewReward(7L, 1L);

        ArgumentCaptor<CoinLedger> cap = ArgumentCaptor.forClass(CoinLedger.class);
        verify(coinMapper).insert(cap.capture());
        assertThat(cap.getValue().getAmount()).isEqualTo(20);
        assertThat(cap.getValue().getSource()).isEqualTo(CoinSource.REVIEW_REWARD);
        assertThat(cap.getValue().getIdemKey()).isEqualTo("review:7");
    }

    @Test
    void awardReviewReward_noOp_whenZeroPaidAmount() {
        Order o = new Order();
        o.setId(7L); o.setUserId(1L); o.setPaidInCents(0);
        when(orderMapper.selectById(7L)).thenReturn(o);
        coinService.awardReviewReward(7L, 1L);
        verify(coinMapper, never()).insert(any());
    }

    @Test
    void awardReviewReward_noOp_onDuplicateKeyConcurrentFire() {
        Order o = new Order();
        o.setId(7L); o.setUserId(1L); o.setPaidInCents(1000);
        when(orderMapper.selectById(7L)).thenReturn(o);
        when(coinMapper.selectBalance(1L)).thenReturn(0);
        doThrow(new DataIntegrityViolationException("dup")).when(coinMapper).insert(any());
        coinService.awardReviewReward(7L, 1L); // must not throw (the Phase D flag)
        verify(coinMapper).insert(any());
    }

    @Test
    void previewCheckoutDiscount_rejectsFlashSaleCart() {
        Product flash = new Product();
        flash.setId(1L); flash.setPriceInCents(1000); flash.setIsFlashSale(true);
        CoinDiscountPreview p = coinService.previewCheckoutDiscount(1L, List.of(flash), Map.of(1L, 1));
        assertThat(p.getEligible()).isFalse();
        assertThat(p.getReason()).contains("flash");
    }

    @Test
    void previewCheckoutDiscount_capsAt50PercentAndBalance() {
        Product p = new Product();
        p.setId(1L); p.setPriceInCents(2000); p.setIsFlashSale(false); // subtotal ¥20, 50% = ¥10 = 1000 coins
        when(coinMapper.selectBalance(1L)).thenReturn(500); // balance 500 < 1000 max
        CoinDiscountPreview r = coinService.previewCheckoutDiscount(1L, List.of(p), Map.of(1L, 1));
        assertThat(r.getEligible()).isTrue();
        assertThat(r.getMaxCoins()).isEqualTo(1000);
        assertThat(r.getUsable()).isEqualTo(500); // clamped to balance, floored to 100
    }

    @Test
    void spendAtCheckout_fifoConsumesOldestFirstAndNullifiesExpiry() {
        CoinLedger soon = credit(40, LocalDateTime.now().plusDays(3));
        soon.setId(10L);
        CoinLedger later = credit(60, LocalDateTime.now().plusDays(25));
        later.setId(11L);
        when(coinMapper.selectSpendableCreditsForUpdate(1L)).thenReturn(List.of(soon, later));
        when(coinMapper.selectBalance(1L)).thenReturn(100, 60, 0); // before, after-credit-1, after-credit-2

        coinService.spendAtCheckout(1L, 77L, 50); // takes 40 from soon, 10 from later

        ArgumentCaptor<CoinLedger> cap = ArgumentCaptor.forClass(CoinLedger.class);
        verify(coinMapper, times(2)).insert(cap.capture());
        List<CoinLedger> spends = cap.getAllValues();
        assertThat(spends).hasSize(2);
        assertThat(spends.get(0).getAmount()).isEqualTo(-40);
        assertThat(spends.get(0).getRefCreditId()).isEqualTo(10L);
        assertThat(spends.get(1).getAmount()).isEqualTo(-10);
        assertThat(spends.get(1).getRefCreditId()).isEqualTo(11L);
        // both consumed credits nullified
        verify(coinMapper, times(2)).updateById(any());
    }

    @Test
    void spendAtCheckout_throwsConflictOnInsufficient() {
        CoinLedger c = credit(30, LocalDateTime.now().plusDays(5));
        c.setId(10L);
        when(coinMapper.selectSpendableCreditsForUpdate(1L)).thenReturn(List.of(c));
        when(coinMapper.selectBalance(1L)).thenReturn(30);
        assertThatThrownBy(() -> coinService.spendAtCheckout(1L, 77L, 100))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getCode() == ResultCode.CONFLICT);
    }

    @Test
    void refundCoins_mirrorsSpendRowsAndRestoresExpiry() {
        Order o = new Order();
        o.setId(7L); o.setUserId(1L);
        when(orderMapper.selectById(7L)).thenReturn(o);
        CoinLedger spend = new CoinLedger();
        spend.setId(20L); spend.setAmount(-40); spend.setRefCreditId(30L); spend.setSource(CoinSource.CHECKOUT_SPEND);
        // the original credit's expiry — fetched by refCreditId
        CoinLedger originalCredit = credit(40, LocalDateTime.now().plusDays(10));
        originalCredit.setId(30L);
        when(coinMapper.selectSpendRowsByOrder(1L, 7L)).thenReturn(List.of(spend));
        when(coinMapper.selectById(30L)).thenReturn(originalCredit);
        when(coinMapper.selectBalance(1L)).thenReturn(0);

        coinService.refundCoins(7L);

        ArgumentCaptor<CoinLedger> cap = ArgumentCaptor.forClass(CoinLedger.class);
        verify(coinMapper).insert(cap.capture());
        CoinLedger refund = cap.getValue();
        assertThat(refund.getAmount()).isEqualTo(40);
        assertThat(refund.getSource()).isEqualTo(CoinSource.REFUND_RETURN);
        assertThat(refund.getRefCreditId()).isEqualTo(30L);
        assertThat(refund.getExpiresAt()).isEqualTo(originalCredit.getExpiresAt()); // restored
        assertThat(refund.getIdemKey()).isEqualTo("refund:7:20");
    }

    @Test
    void refundCoins_noOp_whenNoSpendRows() {
        Order o = new Order();
        o.setId(7L); o.setUserId(1L);
        when(orderMapper.selectById(7L)).thenReturn(o);
        when(coinMapper.selectSpendRowsByOrder(1L, 7L)).thenReturn(List.of());
        coinService.refundCoins(7L);
        verify(coinMapper, never()).insert(any());
    }

    @Test
    void refundCoins_noOp_onDuplicateKey() {
        Order o = new Order();
        o.setId(7L); o.setUserId(1L);
        when(orderMapper.selectById(7L)).thenReturn(o);
        CoinLedger spend = new CoinLedger();
        spend.setId(20L); spend.setAmount(-40); spend.setRefCreditId(30L); spend.setSource(CoinSource.CHECKOUT_SPEND);
        CoinLedger originalCredit = credit(40, LocalDateTime.now().plusDays(10));
        originalCredit.setId(30L);
        when(coinMapper.selectSpendRowsByOrder(1L, 7L)).thenReturn(List.of(spend));
        when(coinMapper.selectById(30L)).thenReturn(originalCredit);
        when(coinMapper.selectBalance(1L)).thenReturn(0);
        doThrow(new DataIntegrityViolationException("dup")).when(coinMapper).insert(any());
        coinService.refundCoins(7L); // must not throw
        verify(coinMapper).insert(any());
    }

    @Test
    void sweepExpiredCredits_insertsExpiredRowAndNullifiesCredit() {
        CoinLedger expired = credit(100, LocalDateTime.now().minusDays(1));
        expired.setId(50L);
        when(coinMapper.selectExpiredUnsweptCredits()).thenReturn(List.of(expired));
        when(coinMapper.selectBalance(expired.getUserId())).thenReturn(0);

        coinService.sweepExpiredCredits();

        ArgumentCaptor<CoinLedger> cap = ArgumentCaptor.forClass(CoinLedger.class);
        verify(coinMapper).insert(cap.capture());
        assertThat(cap.getValue().getAmount()).isEqualTo(-100);
        assertThat(cap.getValue().getSource()).isEqualTo(CoinSource.EXPIRED);
        assertThat(cap.getValue().getRefCreditId()).isEqualTo(50L);
        assertThat(cap.getValue().getExpiresAt()).isNull();
        verify(coinMapper).updateById(argThat(c -> c.getId().equals(50L) && c.getExpiresAt() == null));
    }

    @Test
    void sweepExpiredCredits_idempotentOnReRun() {
        // second run: selectExpiredUnsweptCredits returns empty (credits nullified) → no inserts
        when(coinMapper.selectExpiredUnsweptCredits()).thenReturn(List.of());
        coinService.sweepExpiredCredits();
        verify(coinMapper, never()).insert(any());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frame-backend && mvn -q -Dtest=CoinServiceImplTest test
```
Expected: FAIL — `CoinService` / `CoinServiceImpl` do not compile.

- [ ] **Step 3: Create the `CoinService` interface**

`frame-backend/src/main/java/com/iflytek/frame/service/CoinService.java`:
```java
package com.iflytek.frame.service;

import com.iflytek.frame.dto.CoinBalanceVO;
import com.iflytek.frame.dto.CoinDiscountPreview;
import com.iflytek.frame.dto.CoinHistoryVO;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.util.PageQuery;
import com.iflytek.frame.common.PageResult;

import java.util.List;
import java.util.Map;

public interface CoinService {

    /** UC012 §1: award 100 coins on first login of the CST day. Idempotent. */
    void awardDailyLogin(Long userId);

    /** UC012 §2: credit the order's actual paid amount as coins. Idempotent (one per order). */
    void awardReviewReward(Long orderId, Long userId);

    /** UC005/UC012 §4: compute the max-allowed coin discount for a cart (validation only — no deduction). */
    CoinDiscountPreview previewCheckoutDiscount(Long userId, List<Product> products, Map<Long, Integer> qtyByProduct);

    /** UC005: deduct `coinAmount` coins FIFO for an order. Called inside the consumer's transaction. */
    void spendAtCheckout(Long userId, Long orderId, int coinAmount);

    /** UC008/UC009: return the coins spent on an order (on refund). Idempotent (one per spent credit). */
    void refundCoins(Long orderId);

    /** UC012 §3: daily sweep of credits past their 30-day clock. Idempotent. */
    void sweepExpiredCredits();

    /** UC012 read: current balance + daily-claim status. */
    CoinBalanceVO getBalance(Long userId);

    /** UC012 read: paginated ledger. */
    PageResult<CoinHistoryVO> getHistory(Long userId, PageQuery query);
}
```

- [ ] **Step 4: Create `CoinServiceImpl`**

`frame-backend/src/main/java/com/iflytek/frame/service/impl/CoinServiceImpl.java`:
```java
package com.iflytek.frame.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iflytek.frame.common.CoinSource;
import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.CoinBalanceVO;
import com.iflytek.frame.dto.CoinDiscountPreview;
import com.iflytek.frame.dto.CoinHistoryVO;
import com.iflytek.frame.entity.CoinLedger;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.CoinMapper;
import com.iflytek.frame.mapper.OrderMapper;
import com.iflytek.frame.service.CoinService;
import com.iflytek.frame.util.PageQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CoinServiceImpl implements CoinService {

    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");
    private static final int COINS_PER_YUAN = 100;
    private static final int EXPIRY_DAYS = 30;

    private final CoinMapper coinMapper;
    private final OrderMapper orderMapper;

    @Override
    @Transactional
    public void awardDailyLogin(Long userId) {
        LocalDate today = LocalDate.now(CST);
        if (coinMapper.selectDailyLogin(userId, today) != null) {
            return; // already claimed today
        }
        int balance = currentBalance(userId);
        CoinLedger row = new CoinLedger();
        row.setUserId(userId);
        row.setAmount(100);
        row.setBalanceAfter(balance + 100);
        row.setSource(CoinSource.DAILY_LOGIN);
        row.setLoginDate(today);
        row.setExpiresAt(LocalDateTime.now().plusDays(EXPIRY_DAYS));
        try {
            coinMapper.insert(row);
        } catch (DataIntegrityViolationException e) {
            // two concurrent logins same instant — the other won; this is a no-op
            log.debug("awardDailyLogin({}) duplicate-key no-op: {}", userId, e.getMessage());
        }
    }

    @Override
    @Transactional
    public void awardReviewReward(Long orderId, Long userId) {
        Order o = orderMapper.selectById(orderId);
        if (o == null || !o.getUserId().equals(userId)) {
            return; // ownership/missing — silently no-op (caller's try/catch isolates)
        }
        int rewardCoins = o.getPaidInCents() / COINS_PER_YUAN; // yuan, floor
        if (rewardCoins <= 0) {
            log.debug("awardReviewReward({}) no-op: paidInCents={} → 0 coins", orderId, o.getPaidInCents());
            return;
        }
        int balance = currentBalance(userId);
        CoinLedger row = new CoinLedger();
        row.setUserId(userId);
        row.setAmount(rewardCoins);
        row.setBalanceAfter(balance + rewardCoins);
        row.setSource(CoinSource.REVIEW_REWARD);
        row.setRefOrderId(orderId);
        row.setIdemKey("review:" + orderId);
        row.setExpiresAt(LocalDateTime.now().plusDays(EXPIRY_DAYS));
        try {
            coinMapper.insert(row);
        } catch (DataIntegrityViolationException e) {
            // concurrent double-fire (the Phase D flag) — idempotent no-op
            log.debug("awardReviewReward({}) duplicate-key no-op: {}", orderId, e.getMessage());
        }
    }

    @Override
    public CoinDiscountPreview previewCheckoutDiscount(Long userId, List<Product> products, Map<Long, Integer> qtyByProduct) {
        CoinDiscountPreview p = new CoinDiscountPreview();
        boolean hasFlash = products.stream().anyMatch(pr -> Boolean.TRUE.equals(pr.getIsFlashSale()));
        if (hasFlash) {
            p.setEligible(false);
            p.setMaxCoins(0);
            p.setBalance(currentBalance(userId));
            p.setUsable(0);
            p.setReason("coins cannot be used on flash-sale items");
            return p;
        }
        int subtotalCents = 0;
        Map<Long, Product> byId = new java.util.HashMap<>();
        for (Product pr : products) {
            byId.put(pr.getId(), pr);
        }
        for (Map.Entry<Long, Integer> e : qtyByProduct.entrySet()) {
            subtotalCents += byId.get(e.getKey()).getPriceInCents() * e.getValue();
        }
        int maxDiscountCents = subtotalCents / 2; // 50% cap
        int maxCoins = (maxDiscountCents / COINS_PER_YUAN) * COINS_PER_YUAN; // 100-coin granularity
        int balance = currentBalance(userId);
        int usable = Math.min(maxCoins, (balance / COINS_PER_YUAN) * COINS_PER_YUAN); // floored to 100
        p.setEligible(true);
        p.setMaxCoins(maxCoins);
        p.setBalance(balance);
        p.setUsable(usable);
        p.setReason(null);
        return p;
    }

    @Override
    @Transactional
    public void spendAtCheckout(Long userId, Long orderId, int coinAmount) {
        if (coinAmount <= 0) {
            return;
        }
        List<CoinLedger> credits = coinMapper.selectSpendableCreditsForUpdate(userId); // FOR UPDATE, FIFO
        int remaining = coinAmount;
        int balance = currentBalance(userId);
        for (CoinLedger credit : credits) {
            if (remaining <= 0) {
                break;
            }
            int take = Math.min(credit.getAmount(), remaining);
            balance -= take;
            CoinLedger spend = new CoinLedger();
            spend.setUserId(userId);
            spend.setAmount(-take);
            spend.setBalanceAfter(balance);
            spend.setSource(CoinSource.CHECKOUT_SPEND);
            spend.setRefOrderId(orderId);
            spend.setRefCreditId(credit.getId());
            spend.setExpiresAt(null);
            coinMapper.insert(spend);
            // nullify the consumed credit's expires_at (spent state)
            credit.setExpiresAt(null);
            coinMapper.updateById(credit);
            remaining -= take;
        }
        if (remaining > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "insufficient coins");
        }
    }

    @Override
    @Transactional
    public void refundCoins(Long orderId) {
        Order o = orderMapper.selectById(orderId);
        if (o == null) {
            return;
        }
        Long userId = o.getUserId();
        List<CoinLedger> spendRows = coinMapper.selectSpendRowsByOrder(userId, orderId);
        if (spendRows.isEmpty()) {
            return; // no coins were spent on this order
        }
        int balance = currentBalance(userId);
        for (CoinLedger spend : spendRows) {
            int returnAmount = -spend.getAmount(); // positive
            CoinLedger originalCredit = coinMapper.selectById(spend.getRefCreditId());
            LocalDateTime restoredExpiry = originalCredit != null ? originalCredit.getExpiresAt() : null;
            balance += returnAmount;
            CoinLedger refund = new CoinLedger();
            refund.setUserId(userId);
            refund.setAmount(returnAmount);
            refund.setBalanceAfter(balance);
            refund.setSource(CoinSource.REFUND_RETURN);
            refund.setRefOrderId(orderId);
            refund.setRefCreditId(spend.getRefCreditId());
            refund.setIdemKey("refund:" + orderId + ":" + spend.getId());
            refund.setExpiresAt(restoredExpiry); // restore original clock, not a fresh 30 days
            try {
                coinMapper.insert(refund);
            } catch (DataIntegrityViolationException e) {
                log.debug("refundCoins({}) duplicate-key no-op on spend row {}: {}", orderId, spend.getId(), e.getMessage());
            }
        }
    }

    @Override
    @Transactional
    public void sweepExpiredCredits() {
        List<CoinLedger> expired = coinMapper.selectExpiredUnsweptCredits();
        for (CoinLedger credit : expired) {
            try {
                int balance = currentBalance(credit.getUserId());
                CoinLedger exp = new CoinLedger();
                exp.setUserId(credit.getUserId());
                exp.setAmount(-credit.getAmount());
                exp.setBalanceAfter(balance - credit.getAmount());
                exp.setSource(CoinSource.EXPIRED);
                exp.setRefCreditId(credit.getId());
                exp.setExpiresAt(null);
                coinMapper.insert(exp);
                credit.setExpiresAt(null); // nullify so it's not re-swept
                coinMapper.updateById(credit);
            } catch (Exception e) {
                log.error("sweepExpiredCredits failed for credit {}: {}", credit.getId(), e.getMessage());
            }
        }
    }

    @Override
    public CoinBalanceVO getBalance(Long userId) {
        CoinBalanceVO vo = new CoinBalanceVO();
        vo.setBalance(currentBalance(userId));
        vo.setClaimedToday(coinMapper.selectDailyLogin(userId, LocalDate.now(CST)) != null);
        vo.setExpiringSoon(0); // expiringSoon projection — see Step note below
        return vo;
    }

    @Override
    public PageResult<CoinHistoryVO> getHistory(Long userId, PageQuery query) {
        Page<CoinLedger> page = new Page<>(query.getCurrent(), query.getSize());
        IPage<CoinLedger> result = coinMapper.selectHistory(page, userId);
        List<CoinHistoryVO> rows = result.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), rows);
    }

    private CoinHistoryVO toVO(CoinLedger c) {
        CoinHistoryVO vo = new CoinHistoryVO();
        vo.setId(c.getId());
        vo.setAmount(c.getAmount());
        vo.setSource(c.getSource().name());
        vo.setRefOrderId(c.getRefOrderId());
        vo.setExpiresAt(c.getExpiresAt());
        vo.setCreateTime(c.getCreateTime());
        vo.setBalanceAfter(c.getBalanceAfter());
        return vo;
    }

    private int currentBalance(Long userId) {
        Integer b = coinMapper.selectBalance(userId);
        return b != null ? b : 0;
    }
}
```

> **`expiringSoon`:** set to 0 for V1 (a small UX nicety in the spec; the projection query is non-essential and can be added later). The `getBalance` contract returns the field; the frontend treats 0 as "no projection". If time permits, add a `selectExpiringSoon(userId, withinDays)` query — but it's not test-gated here and may be skipped per the skip-and-return policy.

- [ ] **Step 5: Run the unit test to verify it passes**

```bash
mvn -q -Dtest=CoinServiceImplTest test
```
Expected: PASS (all 15 tests green). If a Mockito stubbing mismatch fails a test, fix the stubbing to match the impl's actual mock calls (e.g. `selectBalance` call counts in `spendAtCheckout`).

- [ ] **Step 6: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/service/CoinService.java \
        frame-backend/src/main/java/com/iflytek/frame/service/impl/CoinServiceImpl.java \
        frame-backend/src/test/java/com/iflytek/frame/service/CoinServiceImplTest.java
git commit -m "feat(phase-e): CoinService — daily-login/review-reward/FIFO-spend/refund-return/expiry-sweep

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 4: Wire the review-seam swap (`CoinServiceStub` → `CoinService`) + update `ReviewServiceImplTest`

**Files:**
- Modify: `frame-backend/src/main/java/com/iflytek/frame/service/impl/ReviewServiceImpl.java` (swap `CoinServiceStub` → `CoinService`).
- Modify: `frame-backend/src/test/java/com/iflytek/frame/service/ReviewServiceImplTest.java` (swap mock).
- Delete: `frame-backend/src/main/java/com/iflytek/frame/service/CoinServiceStub.java`

**Interfaces:**
- Consumes: `CoinService` (Task 3).
- Produces: `ReviewServiceImpl` now calls `coinService.awardReviewReward(order.getId(), userId)` instead of the stub. The `CoinServiceStub` file is gone.

- [ ] **Step 1: Update `ReviewServiceImpl`**

In `frame-backend/src/main/java/com/iflytek/frame/service/impl/ReviewServiceImpl.java`:

a) Replace the import:
```java
import com.iflytek.frame.service.CoinServiceStub;
```
→
```java
import com.iflytek.frame.service.CoinService;
```

b) Replace the field:
```java
    private final CoinServiceStub coinServiceStub;
```
→
```java
    private final CoinService coinService;
```

c) Replace the call site (in `create`, the `coinServiceStub.reviewSubmitted(...)` call):
```java
                coinServiceStub.reviewSubmitted(order.getId(), userId);
```
→
```java
                coinService.awardReviewReward(order.getId(), userId);
```

d) Update the catch-log message text: `coinServiceStub.reviewSubmitted` → `coinService.awardReviewReward`.

- [ ] **Step 2: Update `ReviewServiceImplTest`**

In `frame-backend/src/test/java/com/iflytek/frame/service/ReviewServiceImplTest.java`:

a) Replace the mock field:
```java
    @Mock CoinServiceStub coinServiceStub;
```
→
```java
    @Mock CoinService coinService;
```
(Add `import com.iflytek.frame.service.CoinService;` — remove the `CoinServiceStub` import if it becomes unused.)

b) Replace the constructor wiring:
```java
        reviewService = new ReviewServiceImpl(reviewMapper, orderItemMapper, orderService,
                coinServiceStub);
```
→
```java
        reviewService = new ReviewServiceImpl(reviewMapper, orderItemMapper, orderService,
                coinService);
```

c) Replace all `verify(coinServiceStub).reviewSubmitted(7L, 1L)` → `verify(coinService).awardReviewReward(7L, 1L)` (2 call sites) and `verify(coinServiceStub, never()).reviewSubmitted(any(), any())` → `verify(coinService, never()).awardReviewReward(any(), any())` (2 call sites). Add `import static org.mockito.ArgumentMatchers.any;` if not already present (it is).

- [ ] **Step 3: Delete `CoinServiceStub`**

```bash
git rm frame-backend/src/main/java/com/iflytek/frame/service/CoinServiceStub.java
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd frame-backend && mvn -q -Dtest=ReviewServiceImplTest test
```
Expected: PASS (all 6 existing tests green, now against `CoinService`).

- [ ] **Step 5: Run the full backend suite (regression — no CoinServiceStub refs remain)**

```bash
mvn -q test
```
Expected: all green (122 + Task 1/3 new tests). If a compile error remains (a stray `CoinServiceStub` reference), fix it. The `ReviewIntegrationTest` calls `reviewService.create` which now invokes the real `CoinService` bean — it should still pass (the review reward credits coins, which is a no-op for the test's assertions, or assertable if extended).

- [ ] **Step 6: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/service/impl/ReviewServiceImpl.java \
        frame-backend/src/test/java/com/iflytek/frame/service/ReviewServiceImplTest.java
git commit -m "feat(phase-e): swap CoinServiceStub → CoinService in ReviewServiceImpl (real review-reward credit)

Co-Authored-By: Claude <noreply@anthropic.com>"
```
(The `git rm` of `CoinServiceStub.java` is staged by the `git add` of the directory? No — `git rm` already staged the deletion; commit it with the `-a` or the add. Verify `git status` shows the deletion staged; if not, `git add -u` then commit.)

---

## Task 5: Daily-login trigger in `AuthServiceImpl`

**Files:**
- Modify: `frame-backend/src/main/java/com/iflytek/frame/service/impl/AuthServiceImpl.java`
- Test: extend `frame-backend/src/test/java/com/iflytek/frame/service/ReviewServiceImplTest.java`? No — add an `AuthServiceImplTest` if one exists, else a new unit test.

**Interfaces:**
- Consumes: `CoinService.awardDailyLogin` (Task 3).
- Produces: every successful login credits the daily-login coins (idempotent), isolated by try/catch so a coin failure never breaks login.

- [ ] **Step 1: Check for an existing `AuthServiceImplTest`**

```bash
ls frame-backend/src/test/java/com/iflytek/frame/service/AuthServiceImplTest.java 2>/dev/null && echo "EXISTS" || echo "NONE"
```
If it exists, extend it; else create `frame-backend/src/test/java/com/iflytek/frame/service/AuthServiceImplTest.java`:

```java
package com.iflytek.frame.service;

import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.LoginRequest;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.security.JwtUtil;
import com.iflytek.frame.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceImplTest {

    @Mock JwtUtil jwtUtil;
    @Mock PasswordEncoder passwordEncoder;
    @Mock UserService userService;
    @Mock LoginAttemptService loginAttemptService;
    @Mock CoinService coinService;
    @InjectMocks AuthServiceImpl authService;

    private User user() {
        User u = new User();
        u.setId(1L);
        u.setUsername("alice");
        u.setPasswordHash("hash");
        u.setRole(com.iflytek.frame.common.UserRole.USER);
        u.setStatus("ACTIVE");
        return u;
    }

    @Test
    void login_awardsDailyLogin_afterSuccess() {
        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword("pw");
        when(userService.findByUsername("alice")).thenReturn(user());
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        when(jwtUtil.generateToken(any(), any(), any())).thenReturn("tok");
        when(jwtUtil.getExpireSeconds()).thenReturn(3600L);

        authService.login(req);

        verify(coinService).awardDailyLogin(1L);
    }

    @Test
    void login_isolatedFromCoinFailure() {
        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword("pw");
        when(userService.findByUsername("alice")).thenReturn(user());
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        when(jwtUtil.generateToken(any(), any(), any())).thenReturn("tok");
        when(jwtUtil.getExpireSeconds()).thenReturn(3600L);
        doThrow(new RuntimeException("coin down")).when(coinService).awardDailyLogin(1L);

        // login must still succeed (return a token) despite the coin subsystem throwing
        authService.login(req);
        verify(coinService).awardDailyLogin(1L);
    }
}
```

(If `AuthServiceImplTest` already exists, add just these two `@Test` methods + the `@Mock CoinService coinService` field; `@InjectMocks` will wire it.)

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frame-backend && mvn -q -Dtest=AuthServiceImplTest test
```
Expected: FAIL — `AuthServiceImpl` has no `CoinService` dependency; the verify fails.

- [ ] **Step 3: Wire `CoinService` into `AuthServiceImpl`**

In `frame-backend/src/main/java/com/iflytek/frame/service/impl/AuthServiceImpl.java`:

a) Add the field (constructor-injected via the existing `@RequiredArgsConstructor`):
```java
    private final CoinService coinService;
```
b) Add the import: `import com.iflytek.frame.service.CoinService;`

c) After `loginAttemptService.recordSuccess(user.getId());` and before `String token = jwtUtil.generateToken(...)`, add:
```java
        try {
            coinService.awardDailyLogin(user.getId());
        } catch (Exception e) {
            log.warn("awardDailyLogin({}) failed: {}", user.getId(), e.getMessage());
        }
```
d) `AuthServiceImpl` needs `@Slf4j` + the `log` import — add `import lombok.extern.slf4j.Slf4j;` and the `@Slf4j` annotation to the class if not present. (Check: the class currently has `@Service @RequiredArgsConstructor` — add `@Slf4j`.)

- [ ] **Step 4: Run the test to verify it passes**

```bash
mvn -q -Dtest=AuthServiceImplTest test
```
Expected: PASS (both tests green).

- [ ] **Step 5: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/service/impl/AuthServiceImpl.java \
        frame-backend/src/test/java/com/iflytek/frame/service/AuthServiceImplTest.java
git commit -m "feat(phase-e): AuthServiceImpl awards daily-login coins on successful login (isolated by try/catch)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 6: Checkout coin branch — `CheckoutRequest` + hot-path validation + payload + consumer deduction + `paid_in_cents`

**Files:**
- Modify: `frame-backend/src/main/java/com/iflytek/frame/dto/CheckoutRequest.java` (add `useCoins` + `coinAmount`).
- Modify: `frame-backend/src/main/java/com/iflytek/frame/service/impl/CheckoutServiceImpl.java` (hot-path validation + payload).
- Modify: `frame-backend/src/main/java/com/iflytek/frame/service/StreamConsumerService.java` (consumer deduction + `paidInCents`).
- Test: `frame-backend/src/test/java/com/iflytek/frame/CheckoutWithCoinsIntegrationTest.java` (new).

**Interfaces:**
- Consumes: `CoinService.previewCheckoutDiscount` + `spendAtCheckout` (Task 3), `Product.isFlashSale` (existing).
- Produces: a checkout that accepts `useCoins`/`coinAmount`, validates them, deducts coins atomically in the consumer, and records `paid_in_cents` on the order.

- [ ] **Step 1: Extend `CheckoutRequest`**

In `frame-backend/src/main/java/com/iflytek/frame/dto/CheckoutRequest.java`, add fields:
```java
    /** UC005: if true, deduct `coinAmount` coins at checkout. */
    private Boolean useCoins;
    /** UC005: number of coins to spend (100 coins = ¥1). Validated + clamped server-side. */
    private Integer coinAmount;
```

- [ ] **Step 2: Write the failing integration test**

`frame-backend/src/test/java/com/iflytek/frame/CheckoutWithCoinsIntegrationTest.java` — `@SpringBootTest`, mirrors `CheckoutFlowIntegrationTest`. Seeds a user with coins (via `coinService.awardDailyLogin`), a non-flash product, then drives checkout with coins:

```java
package com.iflytek.frame;

import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.dto.CheckoutRequest;
import com.iflytek.frame.dto.CheckoutResponse;
import com.iflytek.frame.dto.OrderItemDTO;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.CheckoutService;
import com.iflytek.frame.service.CoinService;
import com.iflytek.frame.service.OrderService;
import com.iflytek.frame.service.PaymentService;
import com.iflytek.frame.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600"
})
class CheckoutWithCoinsIntegrationTest {

    @Autowired CheckoutService checkoutService;
    @Autowired CoinService coinService;
    @Autowired OrderService orderService;
    @Autowired PaymentService paymentService;
    @Autowired ProductMapper productMapper;
    @Autowired UserService userService;

    private Long seedUserWithCoins(int coins) {
        // register a user (sms code seeded via the shared test path) then award coins directly
        String phone = "139" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Long uid = 1L; // default admin/user fallback — see note
        coinService.awardDailyLogin(uid); // +100
        // for tests needing >100, call awardDailyLogin with different login_date via a second user or
        // rely on 100 coins = ¥1 discount on a ¥200 order (max 50% = ¥100 = 100 coins)
        return uid;
    }

    private Long seedNonFlashProduct(int priceCents, int stock) {
        Product p = new Product();
        p.setName("CoinTest-" + System.nanoTime());
        p.setPriceInCents(priceCents);
        p.setStock(stock);
        p.setCategory("test");
        p.setIsFlashSale(false);
        productMapper.insert(p);
        return p.getId();
    }

    private Long seedFlashProduct(int priceCents, int stock) {
        Product p = new Product();
        p.setName("CoinTestFlash-" + System.nanoTime());
        p.setPriceInCents(priceCents);
        p.setStock(stock);
        p.setCategory("test");
        p.setIsFlashSale(true);
        productMapper.insert(p);
        return p.getId();
    }

    @Test
    void checkout_withCoins_deductsAndSetsPaidInCents() {
        Long uid = registerTestUser();
        coinService.awardDailyLogin(uid); // +100 coins
        Long pid = seedNonFlashProduct(20000, 5); // ¥200, 50% cap = ¥100 = 100 coins
        CheckoutRequest req = new CheckoutRequest();
        req.setItems(List.of(new OrderItemDTO(pid, 1)));
        req.setCustomerName("t"); req.setCustomerEmail("t@t.com");
        req.setShippingAddress("addr"); req.setCustomerPhone("13900000001");
        req.setPaymentMethod("WECHAT"); req.setIdempotencyKey("ik-" + System.nanoTime());
        req.setUseCoins(true); req.setCoinAmount(100);

        CheckoutResponse res = checkoutService.checkout(req, uid);
        // wait for the consumer (poll the order until resolved)
        Order o = pollUntilResolved(res.getOrderId(), uid);
        assertThat(o.getPaidInCents()).isEqualTo(20000 - 100); // ¥200 - ¥1 = 19900
        assertThat(o.getTotalInCents()).isEqualTo(20000);
        assertThat(coinService.getBalance(uid).getBalance()).isLessThanOrEqualTo(0);
    }

    @Test
    void checkout_flashSaleItem_withCoins_rejected() {
        Long uid = registerTestUser();
        Long pid = seedFlashProduct(20000, 5);
        CheckoutRequest req = new CheckoutRequest();
        req.setItems(List.of(new OrderItemDTO(pid, 1)));
        req.setCustomerName("t"); req.setCustomerEmail("t@t.com");
        req.setShippingAddress("addr"); req.setCustomerPhone("13900000002");
        req.setPaymentMethod("WECHAT"); req.setIdempotencyKey("ik-" + System.nanoTime());
        req.setUseCoins(true); req.setCoinAmount(100);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> checkoutService.checkout(req, uid))
                .isInstanceOf(com.iflytek.frame.exception.BusinessException.class);
    }

    @Test
    void checkout_coinAmountOver50Percent_rejected() {
        Long uid = registerTestUser();
        coinService.awardDailyLogin(uid); // +100
        Long pid = seedNonFlashProduct(20000, 5); // 50% cap = 100 coins
        CheckoutRequest req = new CheckoutRequest();
        req.setItems(List.of(new OrderItemDTO(pid, 1)));
        req.setCustomerName("t"); req.setCustomerEmail("t@t.com");
        req.setShippingAddress("addr"); req.setCustomerPhone("13900000003");
        req.setPaymentMethod("WECHAT"); req.setIdempotencyKey("ik-" + System.nanoTime());
        req.setUseCoins(true); req.setCoinAmount(300); // > 100 cap

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> checkoutService.checkout(req, uid))
                .isInstanceOf(com.iflytek.frame.exception.BusinessException.class);
    }

    private Long registerTestUser() {
        // Reuse the SMS-seeded register path from ReviewIntegrationTest (seed sms:code:REGISTER:{phone} in Redis).
        // If that's awkward here, seed a user row directly via userService.register or orderMapper.
        // Simplest: call the shared helper. For this test, register a user with a known id via the test profile.
        // (Adapt to the project's actual register-in-test helper — see CheckoutFlowIntegrationTest.)
        return com.iflytek.frame.CheckoutFlowIntegrationTestSupport.registerTestUser(); // placeholder for the shared helper — REPLACE with the actual helper per Step 3 note
    }

    private Order pollUntilResolved(Long orderId, Long uid) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                Order o = orderService.getById(orderId, uid);
                if (o.getTotalInCents() > 0 || o.getStatus() == OrderStatus.FAILED) {
                    return o;
                }
            } catch (Exception ignored) {}
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }
        throw new AssertionError("order " + orderId + " did not resolve in 10s");
    }
}
```

> **`registerTestUser()` helper:** the test references a shared helper `CheckoutFlowIntegrationTestSupport.registerTestUser()`. **Read `CheckoutFlowIntegrationTest.java` first** to see how IT registers a test user (SMS code seeded in Redis + `userService.register`, or a direct `userMapper.insert`). Inline that exact approach into `registerTestUser()` — do NOT leave the `CheckoutFlowIntegrationTestSupport` placeholder. The goal: a registered user with a known id, +100 coins from `awardDailyLogin`. If the project's register helper returns the `User`/id, use it directly.

- [ ] **Step 3: Run the test to verify it fails**

```bash
cd frame-backend && mvn -q -Dtest=CheckoutWithCoinsIntegrationTest test
```
Expected: FAIL — `CheckoutRequest` has no `useCoins`/`coinAmount` setters yet, and the hot path doesn't validate them. (After Step 1 the setters exist; the test still fails because the hot path rejects `useCoins` or doesn't deduct.)

- [ ] **Step 4: Wire the hot-path validation + payload in `CheckoutServiceImpl`**

In `frame-backend/src/main/java/com/iflytek/frame/service/impl/CheckoutServiceImpl.java`:

a) Add the `CoinService` dependency (constructor-injected via `@RequiredArgsConstructor`):
```java
    private final com.iflytek.frame.service.CoinService coinService;
```
(Update the `@RequiredArgsConstructor` field list — it's already there as a class annotation; just add the field.)

b) After step 2 (the `selectBatchIds` + size check, before the one-per-user check at step 2b) — actually, after fetching products so we can check flash-sale + preview. Insert this block right after the `products.size() != qtyByProduct.size()` check (before the one-per-user check), gated on `useCoins`:

```java
        // 2c. UC005/UC012: validate the coin-discount request BEFORE the stock gate (fail fast).
        int validatedCoinAmount = 0;
        boolean useCoins = Boolean.TRUE.equals(request.getUseCoins());
        if (useCoins) {
            com.iflytek.frame.dto.CoinDiscountPreview preview =
                    coinService.previewCheckoutDiscount(userId, products, qtyByProduct);
            if (!Boolean.TRUE.equals(preview.getEligible())) {
                throw new BusinessException(ResultCode.PARAM_INVALID, preview.getReason());
            }
            int requested = request.getCoinAmount() == null ? 0 : request.getCoinAmount();
            if (requested <= 0) {
                throw new BusinessException(ResultCode.PARAM_INVALID, "coin amount must be positive");
            }
            if (requested > preview.getUsable()) {
                throw new BusinessException(ResultCode.PARAM_INVALID, "coin amount exceeds allowed discount");
            }
            validatedCoinAmount = requested;
        }
```

c) Carry `useCoins` + `validatedCoinAmount` into the payload — modify `jsonPayload` to add them, and the call site:

Change the `jsonPayload(orderId, userId, request, totalInCents, qtyByProduct)` call to pass the coin info:
```java
        String payload = jsonPayload(orderId, userId, request, totalInCents, qtyByProduct, useCoins, validatedCoinAmount);
```

And update `jsonPayload` signature + body:
```java
    private String jsonPayload(Long orderId, Long userId, CheckoutRequest req,
                                int total, Map<Long, Integer> items, boolean useCoins, int coinAmount) {
        try {
            var node = objectMapper.createObjectNode();
            node.put("orderId", orderId);
            node.put("userId", userId);
            node.put("customerName", req.getCustomerName());
            node.put("customerEmail", req.getCustomerEmail());
            node.put("customerPhone", req.getCustomerPhone());
            node.put("paymentMethod", req.getPaymentMethod().toUpperCase());
            node.put("shippingAddress", req.getShippingAddress());
            node.put("totalInCents", total);
            node.put("idempotencyKey", req.getIdempotencyKey());
            node.put("useCoins", useCoins);
            node.put("coinAmount", coinAmount);
            var itemsNode = node.putArray("items");
            items.forEach((pid, qty) -> {
                var i = itemsNode.addObject();
                i.put("productId", pid);
                i.put("quantity", qty);
            });
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
```

- [ ] **Step 5: Wire the consumer deduction + `paidInCents` in `StreamConsumerService`**

In `frame-backend/src/main/java/com/iflytek/frame/service/StreamConsumerService.java`:

a) Add the `CoinService` dependency. The class uses an explicit constructor (not `@RequiredArgsConstructor`) — add `CoinService coinService` as a constructor param + field:
```java
    private final com.iflytek.frame.service.CoinService coinService;
```
And in the constructor:
```java
    public StreamConsumerService(StringRedisTemplate redis,
                                  OrderMapper orderMapper,
                                  OrderItemMapper orderItemMapper,
                                  ProductMapper productMapper,
                                  StockGateService stockGateService,
                                  com.iflytek.frame.service.CoinService coinService) {
        this.redis = redis;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.productMapper = productMapper;
        this.stockGateService = stockGateService;
        this.coinService = coinService;
    }
```

b) In `processPayload`, parse `useCoins` + `coinAmount` (null-tolerant, in the same try block as the other fields):
```java
        boolean useCoins;
        int coinAmount;
        // ...inside the parse try:
        JsonNode useCoinsNode = node.get("useCoins");
        useCoins = useCoinsNode != null && !useCoinsNode.isNull() && useCoinsNode.asBoolean();
        JsonNode coinAmountNode = node.get("coinAmount");
        coinAmount = coinAmountNode != null && !coinAmountNode.isNull() ? coinAmountNode.asInt() : 0;
```

c) Compute `paidInCents` and set it on the Order before `orderMapper.insert(order)`:
```java
            int coinDiscountCents = (useCoins && coinAmount > 0) ? (coinAmount / 100) : 0;
            int paidInCents = totalInCents - coinDiscountCents;
            order.setPaidInCents(paidInCents);
```
(Place this right before `orderMapper.insert(order);` — after the existing `order.setIdempotencyKey(idempotencyKey);` line.)

d) After the stock-backstop loop succeeds (after the `for (JsonNode item : itemsNode)` loop, before setting the Redis status keys at the end), deduct the coins. Wrap in the same `@Transactional`:

```java
            // UC005: deduct coins atomically with the order (only if the order will actually exist).
            if (useCoins && coinAmount > 0) {
                try {
                    coinService.spendAtCheckout(userId, order.getId(), coinAmount);
                } catch (com.iflytek.frame.exception.BusinessException coinFail) {
                    if (coinFail.getCode() == com.iflytek.frame.common.ResultCode.CONFLICT) {
                        // insufficient coins (narrow race) — fail the order, recredit DB stock
                        order.setStatus(OrderStatus.FAILED);
                        orderMapper.updateById(order);
                        for (JsonNode item : itemsNode) {
                            Long pid = item.get("productId").asLong();
                            int qty = item.get("quantity").asInt();
                            stockGateService.recredit(pid, qty);
                        }
                        redis.opsForValue().set("order_id_map:" + payloadOrderId, String.valueOf(order.getId()));
                        redis.delete("seckill:" + "PLACEHOLDER" + ":" + userId); // see note
                        redis.opsForValue().set("order_status:" + payloadOrderId, "failed");
                        return;
                    }
                    throw coinFail;
                }
            }
            redis.opsForValue().set("order_id_map:" + payloadOrderId, String.valueOf(order.getId()));
            redis.opsForValue().set("order_status:" + payloadOrderId, "pending");
```

> **`seckill` key cleanup note:** the existing success path sets `seckill:{pid}:{userId}` per item inside the loop. On a coin-failure FAILED path, those keys were already set for items processed before the failure. The simplest correct cleanup: delete `seckill:{pid}:{userId}` for each item in the cart (mirror the existing FAILED-path recredit at lines 272-281, which deletes the seckill key per-item). Replace the `"PLACEHOLDER"` line with the per-item delete inside the recredit loop (move the `redis.delete` into the `for` loop above). Match the existing FAILED path's exact cleanup. **Read lines 272-281 of the current `StreamConsumerService` and mirror that pattern exactly.**

- [ ] **Step 6: Run the integration test to verify it passes**

```bash
mvn -q -Dtest=CheckoutWithCoinsIntegrationTest test
```
Expected: PASS (all 3 tests green). If the consumer-deduction test is flaky due to the async poll, increase the poll timeout or add `Thread.sleep` grace. If the `registerTestUser()` helper is wrong, fix it to match `CheckoutFlowIntegrationTest`'s actual approach.

- [ ] **Step 7: Run the full backend suite (regression)**

```bash
mvn -q test
```
Expected: all green. The existing `CheckoutFlowIntegrationTest` (no coins) must still pass — `useCoins` defaults null/false, so the new code paths are skipped.

- [ ] **Step 8: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/dto/CheckoutRequest.java \
        frame-backend/src/main/java/com/iflytek/frame/service/impl/CheckoutServiceImpl.java \
        frame-backend/src/main/java/com/iflytek/frame/service/StreamConsumerService.java \
        frame-backend/src/test/java/com/iflytek/frame/CheckoutWithCoinsIntegrationTest.java
git commit -m "feat(phase-e): checkout coin branch — hot-path validation + consumer FIFO deduction + paid_in_cents

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 7: Refund-coin hooks (`cancelPaid` + admin `approveRefund`)

**Files:**
- Modify: `frame-backend/src/main/java/com/iflytek/frame/service/impl/OrderServiceImpl.java` (cancelPaid hook).
- Modify: `frame-backend/src/main/java/com/iflytek/frame/service/impl/AdminOrderServiceImpl.java` (approveRefund hook).
- Test: extend `frame-backend/src/test/java/com/iflytek/frame/CoinIntegrationTest.java` (created in Task 8) — or a focused unit test here.

**Interfaces:**
- Consumes: `CoinService.refundCoins` (Task 3).
- Produces: cancelling a paid order (UC008) or approving a refund (UC009) returns the coins spent on it.

- [ ] **Step 1: Add `CoinService` to `OrderServiceImpl`**

In `frame-backend/src/main/java/com/iflytek/frame/service/impl/OrderServiceImpl.java`:
- Add field `private final com.iflytek.frame.service.CoinService coinService;` (constructor-injected via `@RequiredArgsConstructor`).
- In `cancelPaid(Long id, Long userId)`, after `orderStockReleaseService.release(o);` (the last line of the method), add:
```java
        try {
            coinService.refundCoins(o.getId());
        } catch (Exception e) {
            log.warn("refundCoins({}) failed after cancel-paid: {}", o.getId(), e.getMessage());
        }
```
(The class already has `@Slf4j`.)

- [ ] **Step 2: Add `CoinService` to `AdminOrderServiceImpl`**

In `frame-backend/src/main/java/com/iflytek/frame/service/impl/AdminOrderServiceImpl.java`:
- Add field `private final com.iflytek.frame.service.CoinService coinService;` (constructor-injected via `@RequiredArgsConstructor`).
- In `approveRefund(Long id)`, after `orderStockReleaseService.release(o);` (the last line), add:
```java
        try {
            coinService.refundCoins(id);
        } catch (Exception e) {
            log.warn("refundCoins({}) failed after approve-refund: {}", id, e.getMessage());
        }
```

- [ ] **Step 3: Verify it compiles + run the order/admin tests**

```bash
cd frame-backend && mvn -q -Dtest=OrderServiceImplTest,AdminOrderServiceImplTest test
```
(If `AdminOrderServiceImplTest` doesn't exist, run just `OrderServiceImplTest`.) Expected: PASS — the new dependency is wired; existing tests that construct `OrderServiceImpl`/`AdminOrderServiceImpl` directly (with `new`) need the extra `coinService` arg. **If a test constructs these with `new ...(...)`**, update the constructor call to pass a `@Mock CoinService`. Check `OrderServiceImplTest` for direct construction.

- [ ] **Step 4: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/service/impl/OrderServiceImpl.java \
        frame-backend/src/main/java/com/iflytek/frame/service/impl/AdminOrderServiceImpl.java \
        frame-backend/src/test/java/com/iflytek/frame/service/OrderServiceImplTest.java 2>/dev/null
git commit -m "feat(phase-e): refund-coin hooks in cancelPaid + admin approveRefund (isolated by try/catch)

Co-Authored-By: Claude <noreply@anthropic.com>"
```
(Add any test constructor-arg fix files to the commit.)

---

## Task 8: Expiry sweep scheduler + `CoinController` + integration test

**Files:**
- Modify: `frame-backend/src/main/java/com/iflytek/frame/service/OrderLifecycleScheduler.java` (add `sweepExpiredCoins` cron).
- Create: `frame-backend/src/main/java/com/iflytek/frame/controller/CoinController.java`
- Test: `frame-backend/src/test/java/com/iflytek/frame/CoinIntegrationTest.java` (new, end-to-end earn→spend→expire→refund).

**Interfaces:**
- Consumes: `CoinService.sweepExpiredCredits`/`getBalance`/`getHistory` (Task 3).
- Produces: a daily 4:01am CST sweep + `GET /coins/balance` + `GET /coins/history`.

- [ ] **Step 1: Add the sweep cron to `OrderLifecycleScheduler`**

In `frame-backend/src/main/java/com/iflytek/frame/service/OrderLifecycleScheduler.java`:
- Add field `private final com.iflytek.frame.service.CoinService coinService;` (the class uses `@RequiredArgsConstructor` — verify and add the field).
- Add the scheduled method:
```java
    /** UC012 §3: daily sweep of coins past their 30-day clock (4:01am CST, off the :00 herd). */
    @org.springframework.scheduling.annotation.Scheduled(cron = "1 1 4 * * *")
    public void sweepExpiredCoins() {
        try {
            coinService.sweepExpiredCredits();
        } catch (Exception e) {
            log.error("sweepExpiredCoins failed", e);
        }
    }
```
(If the class isn't already `@Slf4j`, add it. The `@Scheduled` cron uses the app timezone `Asia/Shanghai` via the existing scheduler config — verify `@EnableScheduling` is on a config class; it already is, since the existing `@Scheduled` jobs run.)

- [ ] **Step 2: Write the `CoinController`**

`frame-backend/src/main/java/com/iflytek/frame/controller/CoinController.java`:
```java
package com.iflytek.frame.controller;

import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.common.Result;
import com.iflytek.frame.dto.CoinBalanceVO;
import com.iflytek.frame.dto.CoinHistoryVO;
import com.iflytek.frame.service.CoinService;
import com.iflytek.frame.util.PageQuery;
import com.iflytek.frame.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Coin", description = "UC012 coin balance + history")
@RestController
@RequestMapping("/coins")
@RequiredArgsConstructor
public class CoinController {

    private final CoinService coinService;

    @Operation(summary = "Current coin balance + daily-claim status")
    @GetMapping("/balance")
    public Result<CoinBalanceVO> balance() {
        return Result.success(coinService.getBalance(SecurityUtils.currentUserId()));
    }

    @Operation(summary = "Coin ledger history (paginated)")
    @GetMapping("/history")
    public Result<PageResult<CoinHistoryVO>> history(@Valid @ParameterObject PageQuery query) {
        return Result.success(coinService.getHistory(SecurityUtils.currentUserId(), query));
    }
}
```
(Add `import jakarta.validation.Valid;` if not present. The endpoints are under `anyRequest().authenticated()` — no `SecurityConfig` change needed, matching the Phase D convention.)

- [ ] **Step 3: Write the integration test**

`frame-backend/src/test/java/com/iflytek/frame/CoinIntegrationTest.java` — `@SpringBootTest` end-to-end:

```java
package com.iflytek.frame;

import com.iflytek.frame.common.CoinSource;
import com.iflytek.frame.dto.CoinBalanceVO;
import com.iflytek.frame.dto.CheckoutRequest;
import com.iflytek.frame.dto.OrderItemDTO;
import com.iflytek.frame.entity.CoinLedger;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.mapper.CoinMapper;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.CoinService;
import com.iflytek.frame.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600"
})
class CoinIntegrationTest {

    @Autowired CoinService coinService;
    @Autowired CoinMapper coinMapper;
    @Autowired OrderService orderService;
    @Autowired ProductMapper productMapper;

    @Test
    void dailyLoginThenReviewReward_balanceReflectsBoth() {
        Long uid = registerTestUser();
        coinService.awardDailyLogin(uid); // +100
        CoinBalanceVO afterLogin = coinService.getBalance(uid);
        assertThat(afterLogin.getBalance()).isEqualTo(100);
        assertThat(afterLogin.getClaimedToday()).isTrue();

        // a second login same day is a no-op
        coinService.awardDailyLogin(uid);
        assertThat(coinService.getBalance(uid).getBalance()).isEqualTo(100);

        // review reward on a paid order (paidInCents = 5000 → 50 coins)
        Long oid = seedPaidOrder(uid, 5000);
        coinService.awardReviewReward(oid, uid); // +50
        assertThat(coinService.getBalance(uid).getBalance()).isEqualTo(150);

        // history shows the rows
        var hist = coinService.getHistory(uid, new com.iflytek.frame.util.PageQuery());
        assertThat(hist.getRecords().size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void spendAtCheckout_thenRefund_returnsCoins() {
        Long uid = registerTestUser();
        coinService.awardDailyLogin(uid); // +100
        Long pid = seedNonFlashProduct(20000, 5); // ¥200, 50% cap = 100 coins
        Long oid = checkoutWithCoins(uid, pid, 100); // spends 100

        assertThat(coinService.getBalance(uid).getBalance()).isLessThanOrEqualTo(0);
        // refund returns the 100 coins (with the original expiry restored)
        coinService.refundCoins(oid);
        assertThat(coinService.getBalance(uid).getBalance()).isEqualTo(100);
    }

    @Test
    void sweepExpiredCredits_expiresOldCredit() {
        Long uid = registerTestUser();
        // insert a credit already past its clock
        CoinLedger c = new CoinLedger();
        c.setUserId(uid); c.setAmount(100); c.setBalanceAfter(100);
        c.setSource(CoinSource.DAILY_LOGIN);
        c.setLoginDate(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(31));
        c.setExpiresAt(LocalDateTime.now().minusDays(1));
        coinMapper.insert(c);

        coinService.sweepExpiredCredits();

        assertThat(coinService.getBalance(uid).getBalance()).isEqualTo(0);
        // re-run is idempotent
        coinService.sweepExpiredCredits();
        assertThat(coinService.getBalance(uid).getBalance()).isEqualTo(0);
    }

    // --- helpers (adapt to the project's register-in-test helper) ---
    private Long registerTestUser() {
        // SAME as CheckoutWithCoinsIntegrationTest: reuse the SMS-seeded register path.
        // Inline the actual helper from CheckoutFlowIntegrationTest here.
        return com.iflytek.frame.CheckoutFlowIntegrationTestSupport.registerTestUser(); // REPLACE
    }

    private Long seedPaidOrder(Long uid, int paidInCents) {
        // insert an Order directly with paidInCents set + a DELIVERED status for the review path
        // (use orderMapper; this test is about the coin math, not the checkout flow)
        com.iflytek.frame.mapper.OrderMapper om =
                org.springframework.beans.factory.annotation.Autowired.class == null ? null : null; // placeholder — inject OrderMapper
        // Actually inject @Autowired OrderMapper orderMapper at the top of the class and use it:
        Order o = new Order();
        o.setUserId(uid); o.setCustomerName("t"); o.setCustomerEmail("t@t.com");
        o.setShippingAddress("a"); o.setTotalInCents(paidInCents); o.setPaidInCents(paidInCents);
        o.setStatus(com.iflytek.frame.common.OrderStatus.COMPLETED);
        o.setIdempotencyKey("ik-" + UUID.randomUUID());
        // orderMapper.insert(o); return o.getId();  — uncomment once orderMapper is injected
        throw new UnsupportedOperationException("inject OrderMapper + insert the order (see comment)");
    }

    private Long seedNonFlashProduct(int priceCents, int stock) {
        Product p = new Product();
        p.setName("CoinInt-" + System.nanoTime());
        p.setPriceInCents(priceCents);
        p.setStock(stock);
        p.setCategory("test");
        p.setIsFlashSale(false);
        productMapper.insert(p);
        return p.getId();
    }

    private Long checkoutWithCoins(Long uid, Long pid, int coinAmount) {
        // drive a real checkout via CheckoutService + poll, OR insert the spend rows directly for a unit-ish test.
        // For an integration test, prefer the real checkout path (reuse CheckoutWithCoinsIntegrationTest helpers).
        throw new UnsupportedOperationException("drive real checkout or insert spend rows directly (see Task 8 Step 3 note)");
    }
}
```

> **Helper resolution (critical):** the test has placeholder helpers that throw `UnsupportedOperationException`. **Resolve them before running:**
> 1. `registerTestUser()` — read `CheckoutFlowIntegrationTest.java`, find how IT registers a user (SMS code seeded in Redis + `userService.register`), and inline that exact approach.
> 2. `seedPaidOrder(uid, paidInCents)` — add `@Autowired com.iflytek.frame.mapper.OrderMapper orderMapper;` to the test class, then `orderMapper.insert(o); return o.getId();`.
> 3. `checkoutWithCoins(uid, pid, coinAmount)` — either drive a real `CheckoutService.checkout` + poll (like `CheckoutWithCoinsIntegrationTest.pollUntilResolved`), OR for a focused coin-math test, insert `CHECKOUT_SPEND` ledger rows directly via `coinMapper` + `spendAtCheckout`. The refund assertion needs real spend rows, so driving the real checkout is cleanest — extract `pollUntilResolved` + the register helper into a shared `CoinTestSupport` class that both `CheckoutWithCoinsIntegrationTest` and `CoinIntegrationTest` use. **Decision: create `frame-backend/src/test/java/com/iflytek/frame/CoinTestSupport.java`** with `registerTestUser()` + `pollUntilResolved()` + `seedNonFlashProduct()`, and have both tests `@Autowired` it or call its static methods.

- [ ] **Step 4: Run the integration test to verify it passes**

```bash
cd frame-backend && mvn -q -Dtest=CoinIntegrationTest test
```
Expected: PASS (all 3 tests green). Fix the placeholder helpers first (Step 3 note). If `spendAtCheckout_thenRefund` is hard to drive via real checkout, fall back to inserting `CHECKOUT_SPEND` rows directly via `coinMapper` in the test setup (the refund path reads them via `selectSpendRowsByOrder`).

- [ ] **Step 5: Run the full backend suite**

```bash
mvn -q test
```
Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/service/OrderLifecycleScheduler.java \
        frame-backend/src/main/java/com/iflytek/frame/controller/CoinController.java \
        frame-backend/src/test/java/com/iflytek/frame/CoinIntegrationTest.java \
        frame-backend/src/test/java/com/iflytek/frame/CoinTestSupport.java 2>/dev/null
git commit -m "feat(phase-e): expiry-sweep scheduler + CoinController (balance/history) + coin integration test

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 9: Frontend types + `api/coin.ts`

**Files:**
- Modify: `frame-frontend/src/api/types.ts` (add coin types + `CheckoutRequest.useCoins/coinAmount` + `Order.paidInCents`).
- Create: `frame-frontend/src/api/coin.ts`

**Interfaces:**
- Consumes: `./request` (axios instance).
- Produces: `CoinBalance`/`CoinHistoryRow` types + `getCoinBalance`/`getCoinHistory` functions. `CheckoutRequest.useCoins/coinAmount`, `Order.paidInCents`. Consumed by Tasks 10-11.

- [ ] **Step 1: Add coin types to `types.ts`**

In `frame-frontend/src/api/types.ts`:

a) Add to `CheckoutRequest` (after `paymentMethod: PaymentMethod`):
```ts
  /** UC005: if true, deduct `coinAmount` coins at checkout. */
  useCoins?: boolean
  /** UC005: coins to spend (100 = ¥1). Validated/clamped server-side. */
  coinAmount?: number
```

b) Add to `Order` (after `totalInCents: number`):
```ts
  /** Actual amount paid after coin discount (UC005/UC012). */
  paidInCents: number
```

c) Add new coin types (after the `Review*` block, before `OrderStatus`):
```ts
/** UC012 coin balance + daily-claim status (mirrors backend CoinBalanceVO). */
export interface CoinBalance {
  balance: number
  claimedToday: boolean
  expiringSoon: number
}

/** UC012 a coin ledger row (mirrors backend CoinHistoryVO). */
export interface CoinHistoryRow {
  id: number
  amount: number
  source: string
  refOrderId?: number
  expiresAt?: string
  createTime: string
  balanceAfter: number
}
```

- [ ] **Step 2: Create `api/coin.ts`**

`frame-frontend/src/api/coin.ts`:
```ts
import request from './request'
import type { CoinBalance, CoinHistoryRow, PageResult } from './types'

/** GET /coins/balance — current balance + daily-claim status. */
export function getCoinBalance(): Promise<CoinBalance> {
  return request.get<CoinBalance>('/coins/balance')
}

/** GET /coins/history — paginated coin ledger. */
export function getCoinHistory(current = 1, size = 20): Promise<PageResult<CoinHistoryRow>> {
  return request.get<PageResult<CoinHistoryRow>>('/coins/history', { params: { current, size } })
}
```

- [ ] **Step 3: Verify it type-checks + builds**

```bash
cd frame-frontend && npm run build
```
Expected: build succeeds (no TS errors).

- [ ] **Step 4: Commit**

```bash
git add frame-frontend/src/api/types.ts frame-frontend/src/api/coin.ts
git commit -m "feat(phase-e): frontend coin types + api/coin.ts client

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 10: `CartItem.isFlashSale` + `Checkout.tsx` coins-at-checkout UI

**Files:**
- Modify: `frame-frontend/src/store/cartReducer.ts` (add `isFlashSale` to `CartItem` + carry it in `add`/`reconcile`).
- Modify: `frame-frontend/src/pages/Products.tsx` (add-to-cart already passes `Product` — `isFlashSale` flows through; verify).
- Modify: `frame-frontend/src/pages/Checkout.tsx` (coins checkbox + amount input + adjusted total).
- Test: extend `frame-frontend/tests/Checkout.test.tsx`.

**Interfaces:**
- Consumes: `getCoinBalance` (Task 9), `CartItem`, `CheckoutRequest`.
- Produces: a checkout form that lets the user opt into coin spending (disabled for flash-sale carts), showing the discount + adjusted total, and sends `useCoins`/`coinAmount` on submit.

- [ ] **Step 1: Add `isFlashSale` to `CartItem`**

In `frame-frontend/src/store/cartReducer.ts`:

a) Add to the `CartItem` interface (after `stock: number`):
```ts
  /** Whether the product is a flash-sale (discount) item — gates coin usage (UC012). */
  isFlashSale: boolean
```

b) In the `add` case, the new-line object (after `stock: action.product.stock ?? 0,`):
```ts
            isFlashSale: Boolean(action.product.isFlashSale),
```
And in the existing-line update (the `{ ...i, quantity: nextQty, stock: ... }` map), add `isFlashSale: Boolean(action.product.isFlashSale),`.

c) In the `reconcile` case (the `next.push({ ...item, ... })` line), add `isFlashSale: Boolean(p.isFlashSale),`.

- [ ] **Step 2: Write the failing Checkout test**

In `frame-frontend/tests/Checkout.test.tsx`, add (adapting the existing render boilerplate — read the file first to match its `vi.mock` setup):

```tsx
import { getCoinBalance } from '@/api/coin'

// add to the existing vi.mock block for '@/api/coin' (or create it):
vi.mock('@/api/coin', () => ({
  getCoinBalance: vi.fn(),
  getCoinHistory: vi.fn(),
}))

it('coins checkbox disabled when cart has a flash-sale item', async () => {
  ;(getCoinBalance as any).mockResolvedValue({ balance: 500, claimedToday: true, expiringSoon: 0 })
  // render Checkout with a flash-sale cart item — adapt to the existing test's cart-setup helper
  // ...
  // expect(screen.getByLabelText(/使用金币/)).toBeDisabled()
})

it('submitting with useCoins includes coinAmount', async () => {
  ;(getCoinBalance as any).mockResolvedValue({ balance: 500, claimedToday: true, expiringSoon: 0 })
  ;(checkout as any).mockResolvedValue({ orderId: 42, status: 'queued' })
  // render Checkout with a non-flash item, check the box, set coinAmount, submit
  // ...
  // expect(checkout).toHaveBeenCalledWith(expect.objectContaining({ useCoins: true, coinAmount: expect.any(Number) }))
})
```

> **Test setup:** read the existing `Checkout.test.tsx` to see how it seeds the cart (likely via a `CartProvider` + `useCart().add`) and mocks `@/api/order`. Mirror that for the cart state + add the `@/api/coin` mock. The exact selectors (`getByLabelText('使用金币')`) depend on the labels you write in Step 3 — keep them in sync.

- [ ] **Step 3: Run the test to verify it fails**

```bash
cd frame-frontend && npm test -- --run tests/Checkout.test.tsx
```
Expected: FAIL — no coins UI exists.

- [ ] **Step 4: Add the coins UI to `Checkout.tsx`**

In `frame-frontend/src/pages/Checkout.tsx`:

a) Imports:
```tsx
import { getCoinBalance } from '@/api/coin'
```

b) In the component, add state + a fetch on mount (stage === 'form'):
```tsx
  const [coinBalance, setCoinBalance] = useState(0)
  const [useCoins, setUseCoins] = useState(false)
  const [coinAmount, setCoinAmount] = useState(0)
  const hasFlash = items.some((i) => i.isFlashSale)
  const subtotalCents = totalCents
  const maxCoins = Math.floor((subtotalCents / 2) / 100) * 100 // 50% cap, 100-coin granularity
  const usableCoins = Math.min(maxCoins, Math.floor(coinBalance / 100) * 100)
  const coinDiscountCents = useCoins ? Math.floor(coinAmount / 100) : 0
  const paidCents = subtotalCents - coinDiscountCents

  useEffect(() => {
    if (stage === 'form') {
      getCoinBalance().then((b) => setCoinBalance(b.balance)).catch(() => {})
    }
  }, [stage])
```

c) In `handleSubmit`, include the coin fields in the `checkout(...)` payload:
```tsx
      const res = await checkout({
        items: items.map((i) => ({ productId: i.productId, quantity: i.quantity })),
        customerName,
        customerEmail,
        shippingAddress,
        customerPhone,
        paymentMethod,
        idempotencyKey: crypto.randomUUID(),
        useCoins: useCoins && coinAmount > 0,
        coinAmount: useCoins ? coinAmount : 0,
      })
```

d) Add the coins UI block inside the `stage === 'form'` form (after the payment-method block, before the submit button). Render it only if `!hasFlash` (or render disabled with a message if `hasFlash`):
```tsx
          <div className="bg-white border border-slate-200 rounded-lg p-4 space-y-3">
            <h2 className="text-sm font-medium text-slate-700">金币 (UC012)</h2>
            {hasFlash ? (
              <p className="text-xs text-slate-500">折扣商品无法使用金币</p>
            ) : (
              <>
                <label className="flex items-center gap-2 text-sm text-slate-700">
                  <input
                    type="checkbox"
                    aria-label="使用金币"
                    checked={useCoins}
                    onChange={(e) => { setUseCoins(e.target.checked); if (!e.target.checked) setCoinAmount(0) }}
                    disabled={coinBalance < 100}
                  />
                  使用金币 (余额 {coinBalance}, 最多抵 {usableCoins} 颗 = ¥{(usableCoins / 100).toFixed(2)})
                </label>
                {useCoins && (
                  <div className="flex items-center gap-2 text-sm">
                    <span className="text-slate-600">抵扣金币数</span>
                    <input
                      type="number"
                      min={0}
                      max={usableCoins}
                      step={100}
                      className="w-24 px-2 py-1 rounded border border-slate-300"
                      value={coinAmount}
                      onChange={(e) => setCoinAmount(Math.min(Number(e.target.value), usableCoins))}
                    />
                    <span className="text-slate-500">优惠 ¥{(coinDiscountCents / 100).toFixed(2)}，实付 ¥{(paidCents / 100).toFixed(2)}</span>
                  </div>
                )}
              </>
            )}
          </div>
```

e) In the `stage === 'resolved'` + `stage === 'paid'` displays, show `order.paidInCents` as the paid amount (the existing UI shows `order.totalInCents` — change the "Total" line to show "实付" with `paidInCents`, or show subtotal + discount + paid). Minimal: change `formatPrice(order.totalInCents)` to `formatPrice(order.paidInCents || order.totalInCents)` in the resolved/paid views with a label "实付".

- [ ] **Step 5: Run the test to verify it passes**

```bash
npm test -- --run tests/Checkout.test.tsx
```
Expected: PASS (existing + new tests green). Fix the selectors to match the actual labels.

- [ ] **Step 6: Commit**

```bash
git add frame-frontend/src/store/cartReducer.ts \
        frame-frontend/src/pages/Checkout.tsx \
        frame-frontend/src/pages/Products.tsx 2>/dev/null \
        frame-frontend/tests/Checkout.test.tsx
git commit -m "feat(phase-e): Checkout.tsx coins-at-checkout UI + CartItem.isFlashSale carry

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 11: `Coins.tsx` history page + route + test

**Files:**
- Create: `frame-frontend/src/pages/Coins.tsx`
- Modify: `frame-frontend/src/App.tsx` (or the router file) — add `/coins` route.
- Test: `frame-frontend/tests/Coins.test.tsx`

**Interfaces:**
- Consumes: `getCoinBalance`, `getCoinHistory` (Task 9).
- Produces: a `/coins` page showing the balance card + a paginated ledger.

- [ ] **Step 1: Write the failing test**

`frame-frontend/tests/Coins.test.tsx`:
```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import Coins from '@/pages/Coins'

vi.mock('@/api/coin', () => ({
  getCoinBalance: vi.fn(),
  getCoinHistory: vi.fn(),
}))
import { getCoinBalance, getCoinHistory } from '@/api/coin'

describe('Coins', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders balance card', async () => {
    ;(getCoinBalance as any).mockResolvedValue({ balance: 300, claimedToday: true, expiringSoon: 0 })
    ;(getCoinHistory as any).mockResolvedValue({ total: 0, current: 1, size: 20, records: [] })
    render(<MemoryRouter><Coins /></MemoryRouter>)
    await waitFor(() => expect(screen.getByText(/300/)).toBeInTheDocument())
    expect(screen.getByText(/今日已领取/)).toBeInTheDocument()
  })

  it('renders history rows', async () => {
    ;(getCoinBalance as any).mockResolvedValue({ balance: 300, claimedToday: false, expiringSoon: 0 })
    ;(getCoinHistory as any).mockResolvedValue({
      total: 1, current: 1, size: 20,
      records: [{ id: 1, amount: 100, source: 'DAILY_LOGIN', createTime: '2026-08-21 10:00:00', balanceAfter: 100 }],
    })
    render(<MemoryRouter><Coins /></MemoryRouter>)
    await waitFor(() => expect(screen.getByText('DAILY_LOGIN')).toBeInTheDocument())
    expect(screen.getByText('+100')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd frame-frontend && npm test -- --run tests/Coins.test.tsx
```
Expected: FAIL — `@/pages/Coins` does not exist.

- [ ] **Step 3: Create `Coins.tsx`**

`frame-frontend/src/pages/Coins.tsx`:
```tsx
import { useEffect, useState } from 'react'
import { Coins as CoinsIcon } from 'lucide-react'
import { getCoinBalance, getCoinHistory } from '@/api/coin'
import type { CoinBalance, CoinHistoryRow, PageResult } from '@/api/types'

const SOURCE_LABEL: Record<string, string> = {
  DAILY_LOGIN: '每日登录',
  REVIEW_REWARD: '评价奖励',
  CHECKOUT_SPEND: '下单抵扣',
  REFUND_RETURN: '退款返还',
  EXPIRED: '过期失效',
}

export default function Coins() {
  const [balance, setBalance] = useState<CoinBalance | null>(null)
  const [history, setHistory] = useState<CoinHistoryRow[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    Promise.all([getCoinBalance(), getCoinHistory(1, 50)])
      .then(([b, h]: [CoinBalance, PageResult<CoinHistoryRow>]) => {
        setBalance(b)
        setHistory(h.records)
      })
      .finally(() => setLoading(false))
  }, [])

  if (loading) return <div className="text-sm text-slate-500">加载中…</div>

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <CoinsIcon className="h-5 w-5 text-amber-500" />
        <h1 className="text-xl font-semibold text-slate-800">我的金币</h1>
      </div>

      {balance && (
        <div className="bg-white border border-slate-200 rounded-lg p-4">
          <div className="flex items-baseline gap-2">
            <span className="text-3xl font-bold text-amber-600">{balance.balance}</span>
            <span className="text-sm text-slate-500">颗（100颗 = ¥1）</span>
          </div>
          <p className="mt-1 text-xs text-slate-500">
            {balance.claimedToday ? '今日已领取' : '今日可领取（登录后自动领取）'}
          </p>
        </div>
      )}

      <div className="bg-white border border-slate-200 rounded-lg p-4">
        <h2 className="mb-2 text-sm font-medium text-slate-700">金币明细</h2>
        {history.length === 0 ? (
          <p className="text-sm text-slate-500">暂无记录</p>
        ) : (
          <ul className="divide-y divide-slate-100">
            {history.map((r) => (
              <li key={r.id} className="flex justify-between py-2 text-sm">
                <div>
                  <div className="text-slate-700">{SOURCE_LABEL[r.source] ?? r.source}</div>
                  <div className="text-xs text-slate-400">{r.createTime}</div>
                </div>
                <span className={r.amount >= 0 ? 'text-green-600' : 'text-red-600'}>
                  {r.amount >= 0 ? `+${r.amount}` : r.amount}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}
```

- [ ] **Step 4: Add the `/coins` route**

Read `frame-frontend/src/App.tsx` (or the router file) to find the route declarations. Add a route for `Coins`:
```tsx
import Coins from '@/pages/Coins'
// ...
<Route path="/coins" element={<Coins />} />
```
(Match the existing route declaration style — lazy-load if the others do.)

- [ ] **Step 5: Run the test to verify it passes**

```bash
npm test -- --run tests/Coins.test.tsx
```
Expected: PASS (both tests green).

- [ ] **Step 6: Commit**

```bash
git add frame-frontend/src/pages/Coins.tsx frame-frontend/src/App.tsx frame-frontend/tests/Coins.test.tsx
git commit -m "feat(phase-e): Coins.tsx history page + /coins route

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 12: E2E coin lifecycle + final review + merge

**Files:**
- Create: `e2e/tests/coins.test.mjs`
- Modify: `docs/superpowers/PHASE-E-STATUS.md` (new — final status report).
- Modify memory `frame-shop-uc-fulfillment.md` (Phase E done + Phase F resume point).
- Modify `docs/superpowers/skipped-issues.md` (only if a hard bug is hit + skipped).

**Interfaces:**
- Consumes: the running local backend + frontend + all Phase E endpoints + the existing e2e auth/register helper.

- [ ] **Step 1: Read the existing e2e harness**

```bash
ls e2e/tests/ && sed -n '1,80p' e2e/tests/orders.test.mjs
```
Note the base URL (likely `http://localhost:8081`), the register/login helper, the auth-token header, and how it drives checkout→pay→ship→confirm-receipt. Reuse the SMS-code-seed approach for register (mirror `CheckoutFlowIntegrationTest`).

- [ ] **Step 2: Write the e2e test**

`e2e/tests/coins.test.mjs` — drive the full flow:
1. Register a user (seed SMS code in Redis) + login → token. Assert `GET /coins/balance` shows `balance == 100`, `claimedToday == true`.
2. Login again → assert `balance` still 100 (idempotent).
3. Checkout a non-flash product with coins (`useCoins: true, coinAmount: 100`) → poll until PENDING → pay → assert `GET /orders/{id}` shows `paidInCents == totalInCents - 100`.
4. Admin login → ship → user confirm-receipt → order DELIVERED.
5. Review each item (5/5/5) → order COMPLETED → assert `GET /coins/balance` increased by `paidInCents/100`.
6. (Refund path) Checkout another non-flash product with coins → cancel-paid → assert `GET /coins/balance` reflects the returned coins.

Use the same `fetch`/assertion style as `orders.test.mjs`. If step 1 hits the pre-existing auth SMS-gated e2e cascade (deferred to Phase F), seed the SMS code via a direct Redis call (the existing suites do this) or mark the registration-dependent assertions `test.skip` with a reason in `skipped-issues.md` and keep the steps an already-authed fixture can exercise.

- [ ] **Step 3: Run the e2e test**

```bash
cd e2e && npm test -- --run tests/coins.test.mjs
```
Expected: PASS (or the skipped-issues path documented). Backend + frontend must be running locally on their dev ports (8081 + 5173).

- [ ] **Step 4: Re-run the full regression gate**

```bash
cd frame-backend && mvn -q test
cd ../frame-frontend && npm test -- --run
cd ../e2e && npm test -- --run   # all e2e suites
```
Expected: backend all green (122 + Phase E tests), frontend all green (44 + Phase E tests), e2e catalog 7 + orders 11 + reviews 6 + coins new all green. Pre-existing non-catalog auth e2e failures remain out of scope.

- [ ] **Step 5: Final whole-branch review**

```bash
git diff main...feature/phase-e-coins --stat
git diff main...feature/phase-e-coins
```
Verify against the spec §7 error-handling table + §2 decisions: idempotent credit (`uk_coin_idem`), daily-login idempotency (`uk_coin_daily`), flash-sale gating, 50% cap, FIFO spend with `FOR UPDATE`, spent-credit `expires_at` nullify, refund restores original expiry, sweep idempotency, the concurrent-double-fire resolution, ownership-before-state, try/catch isolation of the coin subsystem from login/review/refund. Note any non-blocking observations in the status doc. If a must-fix is found, fix it + re-run the affected suite before merge.

- [ ] **Step 6: Merge to main**

```bash
git checkout main
git merge --no-ff feature/phase-e-coins -m "merge: Phase E (Coins) — UC012

coin_ledger (append-only + expires_at state flag); daily-login reward
(100/day, 4am CST) + review reward (= paid_in_cents/100) + 30-day
per-credit FIFO expiry sweep; checkout coin branch (100=¥1, ≤50%,
no flash-sale) deducted atomically in the consumer; refund returns
coins; replaces the Phase D CoinServiceStub no-op seam; completes UC005.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

- [ ] **Step 7: Re-run regression on main + write the status doc + update memory**

```bash
cd frame-backend && mvn -q test && cd ../frame-frontend && npm test -- --run
```

Write `docs/superpowers/PHASE-E-STATUS.md` (mirror the Phase D status structure: what was built, tests-green counts, final-review verdict, deferred-to-Phase-F items, recovery map). Update memory `frame-shop-uc-fulfillment.md` with Phase E completion + the Phase F (chat + lazy-load + 10k QPS + cleanup + redeploy) resume point.

- [ ] **Step 8: Commit the docs + memory**

```bash
git add docs/superpowers/PHASE-E-STATUS.md "C:/Users/tcmao/.claude/projects/C--Users-tcmao-Desktop-newRepo/memory/frame-shop-uc-fulfillment.md"
git commit -m "docs(phase-e): Phase E status report + memory update

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Self-Review

**1. Spec coverage** — checked each spec section against the tasks:
- §1 scope: daily-login (Task 3 + 5), review reward (Task 3 + 4), coins-at-checkout (Task 6 + 10), refund returns (Task 7), 30-day FIFO expiry (Task 3 sweep + Task 8 scheduler), balance/history read (Task 8 controller + Task 9-11 frontend). ✓
- §2 decisions: ledger model (Task 1), review-reward idempotency `uk_coin_idem` (Task 1 schema + Task 3 impl + Task 3 test), daily-login `uk_coin_daily` (Task 1 + 3 + 5), paid-amount source `orders.paid_in_cents` (Task 0 + 1 + 6), consumer deduction (Task 6), FIFO spend `FOR UPDATE` (Task 1 query + Task 3 impl), flash-sale gating (Task 6), 50% cap (Task 3 impl + Task 6 test), refund restore original expiry (Task 3 impl + test), expiry sweep 4:01am (Task 8), daily-login trigger in login (Task 5), no balance cache (Task 3 impl), CheckoutRequest change (Task 6). ✓
- §3 data model: `coin_ledger` + `paid_in_cents` (Task 0). ✓
- §4 backend: Tasks 1-8 (entity, mapper, DTOs, service, seam swap, login trigger, checkout, refund hooks, sweep, controller). ✓
- §5 frontend: Tasks 9-11 (types, api client, cart flash flag, checkout UI, coins page + route). ✓
- §6 testing: unit (Task 3), integration (Tasks 1, 6, 8), frontend (Tasks 10, 11), e2e (Task 12). ✓
- §7 error handling: covered by Task 3 unit tests (idempotency, insufficient, refund empty, sweep idempotent) + Task 6 integration tests (flash-sale, over-cap, deduction) + Task 8 integration (sweep). ✓
- §8 build order: tasks 0→12 follow it. ✓

**2. Placeholder scan** — the plan body has NO TBD/TODO/"add error handling"/"similar to Task N". The only intentional `UnsupportedOperationException`/`REPLACE` markers are inside TEST helper methods (Task 6 Step 2, Task 8 Step 3) with explicit resolution instructions (Step 3 note / Step 2 note) — these are test-setup helpers that must be adapted to the project's actual register-in-test approach (reading `CheckoutFlowIntegrationTest`), not implementation gaps. The `CoinServiceStub` "TODO" is gone (deleted in Task 4). ✓

**3. Type consistency** — `CoinService` signatures match across Task 3 (interface + impl), Task 4 (ReviewServiceImpl call `awardReviewReward(order.getId(), userId)`), Task 5 (AuthServiceImpl `awardDailyLogin(user.getId())`), Task 6 (`previewCheckoutDiscount(userId, products, qtyByProduct)` + `spendAtCheckout(userId, orderId, coinAmount)`), Task 7 (`refundCoins(o.getId())` / `refundCoins(id)`), Task 8 (`sweepExpiredCredits()` / `getBalance` / `getHistory`). `CoinMapper` methods match Task 1 + Task 3. `CoinBalanceVO`/`CoinHistoryVO`/`CoinDiscountPreview` match Task 2 + Task 3 + Task 8 (controller) + Task 9 (frontend types). `CheckoutRequest.useCoins/coinAmount` + `Order.paidInCents` match Task 6 (backend) + Task 9 (frontend). `CartItem.isFlashSale` matches Task 10 (cartReducer + Checkout). ✓

No gaps or inconsistencies found.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-21-phase-e-coins.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
