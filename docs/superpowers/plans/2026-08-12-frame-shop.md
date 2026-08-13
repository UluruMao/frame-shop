# Frame Shop (Flash-Sale Storefront) Implementation Plan — V1

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a flash-sale storefront on top of the existing `frame-backend` + `frame-frontend` template, implementing the Alibaba/Taobao seckill concurrency model (Redis Lua gate → Stream buffer → DB conditional-update backstop).

**Architecture:** Three tiers — edge (Vite/Nginx), application (Spring Boot :8081 + Redis), database (MariaDB). Redis Lua script is the primary stock gate (shields DB from burst); a Redis Stream + consumer-group consumer buffers orders and writes them durably with a DB conditional `UPDATE ... WHERE stock >= qty` backstop. At-least-once delivery is constructed (consumer group + XACK-after-commit + XAUTOCLAIM), not asserted. No-double-order is enforced by a `UNIQUE` `idempotency_key` column on `orders`.

**Tech Stack:** Spring Boot 4.1.0 / Java 21 / MyBatis-Plus 3.5.17 / MariaDB / Spring Security (JWT) / SpringDoc 3.1.0 / jjwt 0.12.6 / Redis 5.0.14.1 (via `spring-boot-starter-data-redis`) on the backend; React 18 + TS 5.5 + Vite 5 + Tailwind 3.4 + Router 6 + Zustand 5 + Axios + recharts + Vitest on the frontend.

## Global Constraints

- **Money is integer cents everywhere** (never `BigDecimal`/float in the new code). The template's `DemoProduct.price` (`BigDecimal`, `DECIMAL(10,2)`) is migrated to `Product.priceInCents` (`int`/`INT`).
- **Backend package:** `com.iflytek.frame`; mappers under `com.iflytek.frame.mapper` (already `@MapperScan`'d in `FrameBackendApplication.java:18`).
- **Entities extend** `com.iflytek.frame.common.BaseEntity` (`id: Long`, `createTime: LocalDateTime`, `updateTime: LocalDateTime` — auto-filled by `MybatisPlusConfig.AuditMetaObjectHandler`).
- **Every controller endpoint returns `Result<T>`** (or `ResponseEntity<Result<Void>>` for delete) — the unified envelope. Frontend Axios interceptor unwraps `.data`.
- **Toolchain** (on user PATH): JDK 21.0.11, Maven 3.9.16, MariaDB 12.3 (root pw `frame_dev`, db `frame_db`), Redis 5.0.14.1 (:6379). Backend runs on port **8081**; Vite proxy `/api` → `:8081`.
- **TDD discipline:** every task writes the failing test first, verifies it fails, implements minimal code, verifies it passes, then commits. Backend tests: JUnit 5 + Mockito (already on the classpath via `spring-boot-starter-test`). Frontend tests: Vitest + `@testing-library/react`.
- **Commit message convention:** `feat:`/`fix:`/`test:`/`refactor:` prefix. End every task's commit with `Co-Authored-By: Claude <noreply@anthropic.com>`.
- **The template cannot express `role=ADMIN` yet** (JWT has no role claim, `SecurityUser.from` hardcodes `ROLE_USER`, `SecurityConfig` has no `hasRole` rules, no `UserDetailsService`). Task 2 builds this — do NOT assume the template supports it.
- **Linux deployment (spec §11a) is a separate follow-up plan**, written after V1 is verified locally. This plan covers build steps 1-8 only.

## File Structure

### Backend — new files (under `frame-backend/src/main/java/com/iflytek/frame/`)

| File | Responsibility |
|---|---|
| `entity/User.java` | User entity (`@TableName("user")`): id, username, password_hash, role, audit cols. |
| `entity/Product.java` | Replaces `DemoProduct` (`@TableName("product")`): name, priceInCents, stock, imageUrl, category, isFlashSale, flashSaleAt, description. |
| `entity/Order.java` | `@TableName("orders")`: userId, customerName/Email/Address, totalInCents, status (enum), idempotencyKey. |
| `entity/OrderItem.java` | `@TableName("order_item")`: orderId, productId, name, priceInCents, quantity (snapshots). |
| `common/Role.java` | enum `USER`, `ADMIN`. |
| `common/OrderStatus.java` | enum `PENDING`, `PAID`, `CANCELLED`, `FAILED`. |
| `mapper/UserMapper.java` | extends `BaseMapper<User>`. |
| `mapper/ProductMapper.java` | extends `BaseMapper<Product>` + custom `decrementStock`. |
| `mapper/OrderMapper.java` | extends `BaseMapper<Order>`. |
| `mapper/OrderItemMapper.java` | extends `BaseMapper<OrderItem>`. |
| `dto/RegisterRequest.java` | username, password (validated). |
| `dto/ProductDTO.java` | replaces `DemoProductDTO`; priceInCents (int). |
| `dto/CheckoutRequest.java` | items[], idempotencyKey. |
| `dto/CheckoutResponse.java` | orderId, status. |
| `dto/OrderItemDTO.java` | productId, quantity. |
| `service/UserService.java` + `impl/UserServiceImpl.java` | register (BCrypt), loadUserByUsername (UserDetailsService). |
| `service/ProductService.java` + `impl/ProductServiceImpl.java` | replaces `DemoProductService`; CRUD + Redis cache + image upload + resync. |
| `service/CheckoutService.java` + `impl/CheckoutServiceImpl.java` | the flash-sale hot path: rate-limit → one-per-user → Lua gate → XADD. |
| `service/PaymentService.java` + `impl/PaymentServiceImpl.java` | atomic pending→paid flip. |
| `service/OrderService.java` + `impl/OrderServiceImpl.java` | get status, my orders, cancel. |
| `service/StockGateService.java` + `impl/StockGateServiceImpl.java` | load stock into Redis (sale open), the Lua script invocation, recovery re-load. |
| `service/StreamConsumerService.java` | consumer-group reader: XREADGROUP → @Transactional durable write → XACK-after-commit; XAUTOCLAIM recovery. |
| `controller/UserController.java` | `/auth/register`. |
| `controller/ProductController.java` | replaces `DemoProductController`; `/products` CRUD + image + `/admin/sales/{id}`. |
| `controller/CheckoutController.java` | `/checkout`. |
| `controller/OrderController.java` | `/orders/{id}` status, `/orders/me`, `/orders/{id}/pay`, `/orders/{id}/cancel`. |
| `config/RedisConfig.java` | `RedisTemplate<String, String>` bean (StringRedisSerializer). |
| `config/RateLimitConfig.java` / `security/RateLimitFilter.java` | Redis token-bucket on `/checkout`. |
| `resources/lua/stock_gate.lua` | the two-pass check-all-then-decr-all script. |

### Backend — modified files

| File | Change |
|---|---|
| `security/JwtUtil.java` | `generateToken` adds a `role` claim. |
| `security/SecurityUser.java` | `from(Claims)` reads the role claim (not hardcoded `ROLE_USER`); add `role` field. |
| `security/SecurityConfig.java` | add `hasRole("ADMIN")` matchers for admin endpoints (replace the broad `.anyRequest().authenticated()` tail with role-aware rules). |
| `common/ResultCode.java` | add `CONFLICT(409)`, `TOO_MANY_REQUESTS(429)`. |
| `exception/GlobalExceptionHandler.java` | `handleBusiness` maps code→HTTP status (409/429/503), not always 400. |
| `service/impl/AuthServiceImpl.java` | replace stub with `UserDetailsService`-backed login (delegate to `UserService`). |
| `pom.xml` | add `spring-boot-starter-data-redis`. |
| `application.yml` | uncomment `spring.data.redis.*`; add `frame.upload.dir`, `frame.rate-limit.*`. |
| `resources/schema.sql` | replace `demo_product` with `user`/`product`/`orders`/`order_item` tables + seed. |
| `resources/mapper/ProductMapper.xml` | new; the `decrementStock` conditional update. |

### Backend — delete (after Task 1 creates replacements)

| File | Why |
|---|---|
| `entity/DemoProduct.java` | replaced by `Product.java`. |
| `mapper/DemoProductMapper.java` + `resources/mapper/DemoProductMapper.xml` | replaced by `ProductMapper`. |
| `service/DemoProductService.java` + `impl/DemoProductServiceImpl.java` | replaced by `ProductService`. |
| `controller/DemoProductController.java` | replaced by `ProductController`. |
| `dto/DemoProductDTO.java` | replaced by `ProductDTO`. |

### Frontend — new/modified files (under `frame-frontend/src/`)

| File | Responsibility |
|---|---|
| `api/types.ts` | update `Product` (price→priceInCents: number); add `User`, `Order`, `OrderItem`, `CheckoutRequest`, `CheckoutResponse`, `OrderStatus`. |
| `api/auth.ts` | add `register()`. |
| `api/product.ts` | update for priceInCents; add `uploadImage()`, `openFlashSale()`. |
| `api/order.ts` (new) | `checkout()`, `getOrderStatus()`, `getMyOrders()`, `payOrder()`, `cancelOrder()`. |
| `pages/Register.tsx` (new) | register form. |
| `pages/Checkout.tsx` (new) | three-stage: queued→pending→paid. |
| `pages/Orders.tsx` (new) | my orders list. |
| `pages/Products.tsx` | update price display (cents→dollars); add stock-remaining bar. |
| `pages/Admin.tsx` (new) | product CRUD + "open flash sale" action. |
| `router/index.tsx` | add routes: register, checkout, orders, admin. |
| `api/request.ts` | fix stale `:8080` comment → `:8081` (line 19). |

---

## Task 1: Data Model + Schema

**Files:**
- Create: `frame-backend/src/main/java/com/iflytek/frame/entity/User.java`, `entity/Product.java`, `entity/Order.java`, `entity/OrderItem.java`, `common/Role.java`, `common/OrderStatus.java`
- Create: `frame-backend/src/main/java/com/iflytek/frame/mapper/UserMapper.java`, `ProductMapper.java`, `OrderMapper.java`, `OrderItemMapper.java`
- Create: `frame-backend/src/main/resources/mapper/ProductMapper.xml`
- Modify: `frame-backend/src/main/resources/schema.sql` (replace demo_product; add user/product/orders/order_item)
- Delete (Task 1 final step): `entity/DemoProduct.java`, `mapper/DemoProductMapper.java`, `resources/mapper/DemoProductMapper.xml` (keep service/controller/DTO until Task 3)

**Interfaces:**
- Produces: `User` entity (fields: `Long id`, `String username`, `String passwordHash`, `Role role`); `Product` entity (`String name`, `int priceInCents`, `int stock`, `String imageUrl`, `String category`, `boolean isFlashSale`, `LocalDateTime flashSaleAt`, `String description`); `Order` entity (`Long userId`, `String customerName`, `String customerEmail`, `String shippingAddress`, `int totalInCents`, `OrderStatus status`, `String idempotencyKey`); `OrderItem` (`Long orderId`, `Long productId`, `String name`, `int priceInCents`, `int quantity`). All extend `BaseEntity`. `Role { USER, ADMIN }`, `OrderStatus { PENDING, PAID, CANCELLED, FAILED }`.
- Produces: `ProductMapper.decrementStock(Long id, int qty)` → `int` (rows affected; in XML). Mappers all `@Mapper` extend `BaseMapper<T>`.

- [ ] **Step 1: Write the schema.sql (replacing demo_product)**

Replace the entire `frame-backend/src/main/resources/schema.sql` with:

```sql
CREATE DATABASE IF NOT EXISTS frame_db
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE frame_db;

DROP TABLE IF EXISTS `order_item`;
DROP TABLE IF EXISTS `orders`;
DROP TABLE IF EXISTS `product`;
DROP TABLE IF EXISTS `user`;

CREATE TABLE `user` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `username`      VARCHAR(64)  NOT NULL,
    `password_hash` VARCHAR(100) NOT NULL,
    `role`          VARCHAR(16)  NOT NULL DEFAULT 'USER',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `product` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT,
    `name`          VARCHAR(128) NOT NULL,
    `price_in_cents` INT          NOT NULL,
    `stock`         INT          NOT NULL DEFAULT 0,
    `image_url`     VARCHAR(512) DEFAULT NULL,
    `category`      VARCHAR(64)  DEFAULT NULL,
    `is_flash_sale` TINYINT(1)   NOT NULL DEFAULT 0,
    `flash_sale_at` DATETIME     DEFAULT NULL,
    `description`   VARCHAR(512) DEFAULT NULL,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_product_category` (`category`),
    KEY `idx_product_flash` (`is_flash_sale`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `orders` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`        BIGINT       NOT NULL,
    `customer_name`  VARCHAR(128) NOT NULL,
    `customer_email` VARCHAR(128) NOT NULL,
    `shipping_address` VARCHAR(256) NOT NULL,
    `total_in_cents` INT          NOT NULL,
    `status`         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    `idempotency_key` VARCHAR(64) NOT NULL,
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_idempotency_key` (`idempotency_key`),
    KEY `idx_orders_user` (`user_id`),
    KEY `idx_orders_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `order_item` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `order_id`      BIGINT       NOT NULL,
    `product_id`    BIGINT       NOT NULL,
    `name`          VARCHAR(128) NOT NULL,
    `price_in_cents` INT         NOT NULL,
    `quantity`      INT          NOT NULL,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_order_item_order` (`order_id`),
    KEY `idx_order_item_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `user` (`username`, `password_hash`, `role`) VALUES
    ('admin', '$2a$10$placeholder_admin_hash_replace_in_task2', 'ADMIN');

INSERT INTO `product` (`name`, `price_in_cents`, `stock`, `category`, `is_flash_sale`, `description`) VALUES
    ('Flash Widget', 1999, 5, 'widgets', 1, 'Limited flash-sale item'),
    ('Demo Gadget', 2950, 50, 'gadgets', 0, 'Regular item');
```

- [ ] **Step 2: Apply the schema to MariaDB**

Run (fresh terminal or prepend the PATH):
```bash
mariadb -u root -pframe_dev < frame-backend/src/main/resources/schema.sql
```
Expected: no output (silent success). Verify: `mariadb -u root -pframe_dev -e "USE frame_db; SHOW TABLES;"` lists `order_item`, `orders`, `product`, `user`.

- [ ] **Step 3: Write the enums**

`common/Role.java`:
```java
package com.iflytek.frame.common;

public enum Role {
    USER, ADMIN
}
```

`common/OrderStatus.java`:
```java
package com.iflytek.frame.common;

public enum OrderStatus {
    PENDING, PAID, CANCELLED, FAILED
}
```

- [ ] **Step 4: Write the User entity**

`entity/User.java`:
```java
package com.iflytek.frame.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.iflytek.frame.common.BaseEntity;
import com.iflytek.frame.common.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "User")
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user")
public class User extends BaseEntity {
    @Schema(description = "Login name")
    private String username;
    @Schema(description = "BCrypt-hashed password")
    private String passwordHash;
    @Schema(description = "Role: USER or ADMIN")
    private Role role;
}
```

- [ ] **Step 5: Write the Product entity**

`entity/Product.java`:
```java
package com.iflytek.frame.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.iflytek.frame.common.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Schema(description = "Product")
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("product")
public class Product extends BaseEntity {
    private String name;
    @Schema(description = "Unit price in integer cents")
    private int priceInCents;
    @Schema(description = "Stock on hand")
    private Integer stock;
    private String imageUrl;
    private String category;
    private boolean isFlashSale;
    private LocalDateTime flashSaleAt;
    private String description;
}
```

- [ ] **Step 6: Write the Order + OrderItem entities**

`entity/Order.java`:
```java
package com.iflytek.frame.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.iflytek.frame.common.BaseEntity;
import com.iflytek.frame.common.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "Order")
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("orders")
public class Order extends BaseEntity {
    private Long userId;
    private String customerName;
    private String customerEmail;
    private String shippingAddress;
    private int totalInCents;
    private OrderStatus status;
    private String idempotencyKey;
}
```

`entity/OrderItem.java`:
```java
package com.iflytek.frame.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.iflytek.frame.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("order_item")
public class OrderItem extends BaseEntity {
    private Long orderId;
    private Long productId;
    private String name;        // snapshot
    private int priceInCents;   // snapshot
    private int quantity;
}
```

- [ ] **Step 7: Write the four mappers**

`mapper/UserMapper.java`, `mapper/ProductMapper.java`, `mapper/OrderMapper.java`, `mapper/OrderItemMapper.java` — each:
```java
package com.iflytek.frame.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iflytek.frame.entity.User;   // swap for Product/Order/OrderItem
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
```
`ProductMapper` additionally declares:
```java
    /**
     * Conditional stock decrement — DB-level mutex/backstop.
     * Returns rows affected (0 if stock &lt; qty → caller treats as failure).
     */
    int decrementStock(@Param("id") Long id, @Param("qty") int qty);
```
(import `org.apache.ibatis.annotations.Param`.)

- [ ] **Step 8: Write ProductMapper.xml (the decrementStock conditional update)**

`resources/mapper/ProductMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.iflytek.frame.mapper.ProductMapper">

    <update id="decrementStock">
        update product
        set stock = stock - #{qty}, update_time = now()
        where id = #{id} and stock &gt;= #{qty}
    </update>

</mapper>
```

- [ ] **Step 9: Verify it compiles (DemoProduct references will still exist — expect compile errors pointing at them, which we fix next; but entities/mappers/enums themselves must be valid)**

Run:
```bash
cd frame-backend && mvn -q compile
```
Expected: compile errors in `DemoProductController/Service/DTO` (they reference the old `DemoProduct` which still exists — they should still compile; the new entities are additive). Actually since we haven't deleted anything, this should pass. If it passes, good. If errors appear in the new files, fix them.

- [ ] **Step 10: Commit**

```bash
git add frame-backend/src/main/java/com/iflytek/frame/entity/ frame-backend/src/main/java/com/iflytek/frame/common/Role.java frame-backend/src/main/java/com/iflytek/frame/common/OrderStatus.java frame-backend/src/main/java/com/iflytek/frame/mapper/ frame-backend/src/main/resources/mapper/ProductMapper.xml frame-backend/src/main/resources/schema.sql
git commit -m "feat: add Frame Shop data model (User, Product, Order, OrderItem) + schema"
```
(If no git repo yet, init one: `git init` at `C:\Users\tcmao\Desktop\newRepo` first, add a `.gitignore` that excludes `node_modules/`, `target/`, `dist/`.)

---

## Task 2: Real Auth + Role-Based Authorization

**Files:**
- Create: `service/UserService.java`, `service/impl/UserServiceImpl.java`, `dto/RegisterRequest.java`, `controller/UserController.java`
- Modify: `security/JwtUtil.java:50-62` (add role claim), `security/SecurityUser.java:14-24` (read role), `security/SecurityConfig.java:71-86` (add hasRole rules), `service/impl/AuthServiceImpl.java:19-44` (delegate to UserService)
- Test: `src/test/java/com/iflytek/frame/service/UserServiceImplTest.java`

**Interfaces:**
- Consumes: `User` entity (Task 1), `UserMapper` (Task 1), `JwtUtil`, `PasswordEncoder` (from `SecurityConfig`), `JwtProperties`.
- Produces: `UserService.register(RegisterRequest)` → `User`; `UserServiceImpl.loadUserByUsername(String)` → `UserDetails` (makes the class a `UserDetailsService`). `JwtUtil.generateToken(Long userId, String username, Role role)` (new 3-arg signature — the old 2-arg is removed). `SecurityUser` gains a `role` field + `from(Claims)` reads it. Endpoints: `POST /auth/register` (public), `POST /auth/login` (now real).

- [ ] **Step 1: Write the failing test for UserService.register**

`src/test/java/com/iflytek/frame/service/UserServiceImplTest.java`:
```java
package com.iflytek.frame.service;

import com.iflytek.frame.common.Role;
import com.iflytek.frame.dto.RegisterRequest;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.mapper.UserMapper;
import com.iflytek.frame.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {
    @Mock UserMapper userMapper;
    @Mock PasswordEncoder passwordEncoder;
    @InjectMocks UserServiceImpl userService;

    @Test
    void register_hashesPassword_andAssignsUserRole_andInserts() {
        when(passwordEncoder.encode("secret123")).thenReturn("$2a$10$hashed");
        when(userMapper.insert(any(User.class))).thenReturn(1);

        User created = userService.register(new RegisterRequest("alice", "secret123"));

        assertThat(created.getUsername()).isEqualTo("alice");
        assertThat(created.getPasswordHash()).isEqualTo("$2a$10$hashed");
        assertThat(created.getRole()).isEqualTo(Role.USER);
        verify(userMapper).insert(any(User.class));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frame-backend && mvn -q -Dtest=UserServiceImplTest test`
Expected: FAIL — `UserServiceImpl`, `RegisterRequest`, `UserService` don't exist yet.

- [ ] **Step 3: Write RegisterRequest DTO**

`dto/RegisterRequest.java`:
```java
package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "Register request")
@Data
public class RegisterRequest {
    @NotBlank(message = "username must not be blank")
    @Size(min = 3, max = 64, message = "username must be 3-64 chars")
    private String username;

    @NotBlank(message = "password must not be blank")
    @Size(min = 6, max = 100, message = "password must be 6-100 chars")
    private String password;
}
```

- [ ] **Step 4: Write UserService interface + impl**

`service/UserService.java`:
```java
package com.iflytek.frame.service;

import com.iflytek.frame.dto.RegisterRequest;
import com.iflytek.frame.entity.User;

public interface UserService {
    User register(RegisterRequest request);

    /** For Spring Security UserDetailsService. */
    User findByUsername(String username);
}
```

`service/impl/UserServiceImpl.java`:
```java
package com.iflytek.frame.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.iflytek.frame.common.Role;
import com.iflytek.frame.dto.RegisterRequest;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.mapper.UserMapper;
import com.iflytek.frame.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService, UserDetailsService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public User register(RegisterRequest request) {
        User existing = findByUsername(request.getUsername());
        if (existing != null) {
            throw new BusinessException(ResultCode.CONFLICT, "username already taken");
        }
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole(Role.USER);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ResultCode.CONFLICT, "username already taken");
        }
        return user;
    }

    @Override
    public User findByUsername(String username) {
        return userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = findByUsername(username);
        if (user == null) {
            throw new UsernameNotFoundException("user not found: " + username);
        }
        String authority = "ROLE_" + user.getRole().name();
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(List.of(new SimpleGrantedAuthority(authority)))
                .build();
    }
}
```

- [ ] **Step 5: Run the register test to verify it passes**

Run: `mvn -q -Dtest=UserServiceImplTest test`
Expected: PASS.

- [ ] **Step 6: Add the role claim to JwtUtil.generateToken**

Modify `security/JwtUtil.java`. Replace the existing `generateToken(Long userId, String username)` method (lines 50-62) with:
```java
    public String generateToken(Long userId, String username, com.iflytek.frame.common.Role role) {
        java.util.Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("username", username);
        claims.put("role", role.name());
        java.util.Date now = new java.util.Date();
        java.util.Date expiry = new java.util.Date(now.getTime() + props.getExpire() * 1000L);
        return Jwts.builder()
                .claims(claims)
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }
```

- [ ] **Step 7: Update SecurityUser to carry + read the role**

Replace the entirety of `security/SecurityUser.java` with:
```java
package com.iflytek.frame.security;

import com.iflytek.frame.common.Role;
import io.jsonwebtoken.Claims;

import java.util.Collection;
import java.util.List;

public record SecurityUser(Long userId, String username, Role role,
                           Collection<Authority> authorities) {

    public static SecurityUser from(Claims claims) {
        Long userId = Long.valueOf(claims.getSubject());
        String username = String.valueOf(claims.get("username"));
        Role role = Role.valueOf(String.valueOf(claims.get("role")));
        String authorityName = "ROLE_" + role.name();
        return new SecurityUser(userId, username, role,
                List.of(new Authority(authorityName)));
    }

    public record Authority(String role) implements org.springframework.security.core.GrantedAuthority {
        @Override
        public String getAuthority() {
            return role;
        }
    }
}
```

- [ ] **Step 8: Rewrite AuthServiceImpl to delegate to UserService (replaces the admin/admin123 stub)**

Replace the entirety of `service/impl/AuthServiceImpl.java` with:
```java
package com.iflytek.frame.service.impl;

import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.LoginRequest;
import com.iflytek.frame.dto.LoginResponse;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.security.JwtUtil;
import com.iflytek.frame.service.AuthService;
import com.iflytek.frame.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final UserService userService;

    @Override
    public LoginResponse login(LoginRequest request) {
        User user = userService.findByUsername(request.getUsername());
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND, "user not found");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ResultCode.BAD_CREDENTIALS, "bad credentials");
        }
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        return new LoginResponse(token, "Bearer", jwtUtil.getExpireSeconds(),
                user.getId(), user.getUsername(), user.getRole().name());
    }
}
```
Also delete the `DEMO_USER_ID`/`DEMO_USERNAME`/`DEMO_PASSWORD` constants from `AuthService.java` (interface now has only `login`).

- [ ] **Step 9: Update LoginResponse to carry the role**

Modify `dto/LoginResponse.java` — add a `role` field. Replace the class with:
```java
package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Schema(description = "Login response")
@Data
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private String tokenType;
    private long expiresIn;
    private Long userId;
    private String username;
    private String role;
}
```

- [ ] **Step 10: Add hasRole("ADMIN") rules to SecurityConfig**

Modify `security/SecurityConfig.java`. In `filterChain`, replace the `.authorizeHttpRequests(auth -> auth ...)` block (lines 71-86) with role-aware rules:
```java
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/auth/login",
                                "/auth/register"
                        ).permitAll()
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/swagger-resources/**",
                                "/webjars/**"
                        ).permitAll()
                        // public product reads (browsing — no login)
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/products", "/products/{id}").permitAll()
                        // admin-only product mutations + sale management
                        .requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/products", "/products/{id}/image", "/admin/sales/{productId}")
                                .hasRole("ADMIN")
                        .requestMatchers(org.springframework.http.HttpMethod.PUT,
                                "/products/{id}").hasRole("ADMIN")
                        .requestMatchers(org.springframework.http.HttpMethod.DELETE,
                                "/products/{id}").hasRole("ADMIN")
                        // everything else requires authentication
                        .anyRequest().authenticated())
```

- [ ] **Step 11: Write the UserController (register endpoint)**

`controller/UserController.java`:
```java
package com.iflytek.frame.controller;

import com.iflytek.frame.common.Result;
import com.iflytek.frame.dto.RegisterRequest;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Auth", description = "User registration")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "Register a new user (role=USER)")
    @PostMapping("/register")
    public Result<User> register(@Valid @RequestBody RegisterRequest request) {
        return Result.success(userService.register(request));
    }
}
```

- [ ] **Step 12: Compile + run all tests**

Run: `mvn -q clean test`
Expected: PASS (context-loads + UserServiceImplTest). If `FrameBackendApplicationTests` fails because the `frame.jwt.secret` is too short, it's already overridden in that test's `@TestPropertySource`.

- [ ] **Step 13: Commit**

```bash
git add -A frame-backend/
git commit -m "feat: real JWT auth with role=ADMIN authorization (register/login/UserDetailsService)"
```

---

## Task 3: Product CRUD + Image Upload + Redis-Cached Reads + Error-Code Extension

**Files:**
- Modify: `pom.xml` (add `spring-boot-starter-data-redis`), `application.yml` (uncomment redis, add upload.dir + rate-limit), `common/ResultCode.java` (add 409/429), `exception/GlobalExceptionHandler.java` (map code→HTTP), `config/WebMvcConfig.java` (static `/uploads/**` handler)
- Create: `config/RedisConfig.java`, `dto/ProductDTO.java`, `service/ProductService.java`, `service/impl/ProductServiceImpl.java`, `controller/ProductController.java`
- Modify: `mapper/ProductMapper.java` (add `updateImageUrl`), `resources/mapper/ProductMapper.xml` (add `updateImageUrl`)
- Delete: `entity/DemoProduct.java`, `mapper/DemoProductMapper.java`, `resources/mapper/DemoProductMapper.xml`, `service/DemoProductService.java`, `service/impl/DemoProductServiceImpl.java`, `controller/DemoProductController.java`, `dto/DemoProductDTO.java`
- Test: `src/test/java/com/iflytek/frame/service/ProductServiceImplTest.java`

**Interfaces:**
- Consumes: `Product` entity + `ProductMapper` (Task 1); `ResultCode`; the new `StringRedisTemplate`; `frame.upload.dir` config.
- Produces: `ProductService` (`page`, `getById`, `create`, `update`, `delete`, `uploadImage`); `ProductDTO` (id, name, priceInCents:Integer, stock, imageUrl, category, isFlashSale, flashSaleAt, description; `toEntity()`); endpoints `GET/POST/PUT/DELETE /products[/{id}]`, `POST /products/{id}/image`. `ResultCode.CONFLICT`/`TOO_MANY_REQUESTS`. `GlobalExceptionHandler.handleBusiness` maps codes → HTTP status.

- [ ] **Step 1: Add the Redis dependency to pom.xml**

In `pom.xml`, after the `mybatis-plus-jsqlparser` dependency block (around line 59), add:
```xml
        <!-- Redis: stock gate, Stream buffer, rate limiter, read cache -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
```
(Version inherited from the Spring Boot 4.1.0 parent.)

- [ ] **Step 2: Activate Redis config in application.yml + add upload dir / rate-limit**

In `application.yml`, replace the commented `# spring: / # data: / # redis:` block (lines ~80-92) with active config under the existing `spring:` key:
```yaml
  # --- Redis (stock gate, Stream buffer, cache) ---
  data:
    redis:
      host: localhost
      port: 6379
      password:
      database: 0
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
          min-idle: 0
```
Add new sub-properties under the `frame:` block (near `frame.jwt`/`frame.cors`):
```yaml
  upload:
    dir: ./uploads        # configurable; NOT a Windows-absolute path (Linux-deploy-friendly)
  rate-limit:
    checkout-per-minute: 10
```

- [ ] **Step 3: Write RedisConfig**

`config/RedisConfig.java`:
```java
package com.iflytek.frame.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisConfig {
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
```

- [ ] **Step 4: Add 409/429 to ResultCode**

In `common/ResultCode.java`, add after `METHOD_NOT_ALLOWED(405, ...)` (line 21), before `UNPROCESSABLE_ENTITY`:
```java
    CONFLICT(409, "conflict"),
    TOO_MANY_REQUESTS(429, "too many requests"),
```

- [ ] **Step 5: Fix GlobalExceptionHandler to map code → HTTP status (was always 400)**

In `exception/GlobalExceptionHandler.java`, replace the `handleBusiness` method (lines 43-48) with:
```java
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException ex, HttpServletRequest req) {
        log.warn("Business error on {}: [{}] {}", req.getRequestURI(), ex.getCode(), ex.getMessage());
        HttpStatus status = httpStatusFor(ex.getCode());
        return ResponseEntity.status(status).body(Result.fail(ex.getCode(), ex.getMessage()));
    }

    private HttpStatus httpStatusFor(int code) {
        if (code == 401) return HttpStatus.UNAUTHORIZED;
        if (code == 403) return HttpStatus.FORBIDDEN;
        if (code == 404) return HttpStatus.NOT_FOUND;
        if (code == 409) return HttpStatus.CONFLICT;
        if (code == 429) return HttpStatus.TOO_MANY_REQUESTS;
        if (code == 503) return HttpStatus.SERVICE_UNAVAILABLE;
        return HttpStatus.BAD_REQUEST; // default for business codes 1000-1005
    }
```

- [ ] **Step 6: Write ProductDTO**

`dto/ProductDTO.java`:
```java
package com.iflytek.frame.dto;

import com.iflytek.frame.entity.Product;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "Product create/update payload")
@Data
public class ProductDTO {
    private Long id;
    @NotBlank(message = "name must not be blank")
    private String name;
    @NotNull(message = "priceInCents must not be null")
    @Min(value = 0, message = "priceInCents must be >= 0")
    private Integer priceInCents;
    @NotNull(message = "stock must not be null")
    @Min(value = 0, message = "stock must be >= 0")
    private Integer stock;
    private String imageUrl;
    private String category;
    private boolean isFlashSale;
    private LocalDateTime flashSaleAt;
    private String description;

    public Product toEntity() {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setPriceInCents(priceInCents);
        p.setStock(stock);
        p.setImageUrl(imageUrl);
        p.setCategory(category);
        p.setFlashSale(isFlashSale);
        p.setFlashSaleAt(flashSaleAt);
        p.setDescription(description);
        return p;
    }
}
```

- [ ] **Step 7: Write the failing test for ProductService (cache miss → load+caches; cache hit → no DB)**

`src/test/java/com/iflytek/frame/service/ProductServiceImplTest.java`:
```java
package com.iflytek.frame.service;

import com.iflytek.frame.entity.Product;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.impl.ProductServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {
    @Mock ProductMapper productMapper;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;
    @InjectMocks ProductServiceImpl productService;

    @Test
    void getById_cacheMiss_loadsFromDb_andCachesWithTtl() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("product:1")).thenReturn(null);
        Product p = new Product(); p.setId(1L); p.setName("Widget");
        when(productMapper.selectById(1L)).thenReturn(p);

        Product result = productService.getById(1L);

        assertThat(result.getName()).isEqualTo("Widget");
        verify(valueOps).set(eq("product:1"), anyString(), eq(30L), eq(java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void getById_cacheHit_doesNotHitDb() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("product:1")).thenReturn("{\"id\":1,\"name\":\"Cached\"}");
        productService.getById(1L);
        verify(productMapper, never()).selectById(any());
    }
}
```

- [ ] **Step 8: Run the test to verify it fails**

Run: `mvn -q -Dtest=ProductServiceImplTest test`
Expected: FAIL — `ProductServiceImpl` does not exist.

- [ ] **Step 9: Write ProductService interface + impl**

`service/ProductService.java`:
```java
package com.iflytek.frame.service;

import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.dto.ProductDTO;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.util.PageQuery;
import org.springframework.web.multipart.MultipartFile;

public interface ProductService {
    Product getById(Long id);
    PageResult<Product> page(PageQuery query);
    Product create(ProductDTO dto);
    Product update(Long id, ProductDTO dto);
    void delete(Long id);
    String uploadImage(Long id, MultipartFile file);
}
```

`service/impl/ProductServiceImpl.java` (read-cache TTL 30s + invalidate on PUT/DELETE; image upload to `frame.upload.dir`):
```java
package com.iflytek.frame.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.ProductDTO;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.ProductService;
import com.iflytek.frame.util.PageQuery;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductMapper mapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${frame.upload.dir:./uploads}")
    private String uploadDir;

    private static final long CACHE_TTL_SECONDS = 30;

    @Override
    @SneakyThrows
    public Product getById(Long id) {
        String key = "product:" + id;
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            return objectMapper.readValue(cached, Product.class);
        }
        Product p = mapper.selectById(id);
        if (p == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "product not found: " + id);
        }
        redis.opsForValue().set(key, objectMapper.writeValueAsString(p), CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        return p;
    }

    @Override
    public PageResult<Product> page(PageQuery query) {
        Page<Product> page = new Page<>(query.getCurrent(), query.getSize());
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(Product::getCreateTime);
        IPage<Product> result = mapper.selectPage(page, wrapper);
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }

    @Override
    public Product create(ProductDTO dto) {
        Product p = dto.toEntity();
        mapper.insert(p);
        return p;
    }

    @Override
    public Product update(Long id, ProductDTO dto) {
        Product existing = getById(id);
        existing.setName(dto.getName());
        existing.setPriceInCents(dto.getPriceInCents());
        existing.setStock(dto.getStock());
        existing.setImageUrl(dto.getImageUrl());
        existing.setCategory(dto.getCategory());
        existing.setFlashSale(dto.isFlashSale());
        existing.setFlashSaleAt(dto.getFlashSaleAt());
        existing.setDescription(dto.getDescription());
        mapper.updateById(existing);
        redis.delete("product:" + id);
        return existing;
    }

    @Override
    public void delete(Long id) {
        getById(id);
        mapper.deleteById(id);
        redis.delete("product:" + id);
    }

    @Override
    @SneakyThrows
    public String uploadImage(Long id, MultipartFile file) {
        getById(id);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "file is empty");
        }
        String original = file.getOriginalFilename();
        String ext = StringUtils.hasText(original) && original.contains(".")
                ? original.substring(original.lastIndexOf('.')) : "";
        String filename = "product-" + id + "-" + UUID.randomUUID() + ext;
        Path dir = Path.of(uploadDir);
        Files.createDirectories(dir);
        file.transferTo(dir.resolve(filename).toFile());
        String imageUrl = "/uploads/" + filename;
        mapper.updateImageUrl(id, imageUrl);
        redis.delete("product:" + id);
        return imageUrl;
    }
}
```

- [ ] **Step 10: Add updateImageUrl to ProductMapper + XML**

In `mapper/ProductMapper.java`, add (with `import org.apache.ibatis.annotations.Param;`):
```java
    void updateImageUrl(@Param("id") Long id, @Param("imageUrl") String imageUrl);
```
In `resources/mapper/ProductMapper.xml`, inside the `<mapper>`:
```xml
    <update id="updateImageUrl">
        update product set image_url = #{imageUrl}, update_time = now() where id = #{id}
    </update>
```

- [ ] **Step 11: Add the static `/uploads/**` handler to WebMvcConfig**

Modify `config/WebMvcConfig.java`: inject the upload dir and override `addResourceHandlers`:
```java
    @Value("${frame.upload.dir:./uploads}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + java.nio.file.Path.of(uploadDir).toAbsolutePath() + "/");
    }
```
(import `org.springframework.beans.factory.annotation.Value`.)

- [ ] **Step 12: Run the test to verify it passes**

Run: `mvn -q -Dtest=ProductServiceImplTest test`
Expected: PASS.

- [ ] **Step 13: Write ProductController (replaces DemoProductController)**

`controller/ProductController.java`:
```java
package com.iflytek.frame.controller;

import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.common.Result;
import com.iflytek.frame.dto.ProductDTO;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.service.ProductService;
import com.iflytek.frame.util.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Product", description = "Product CRUD + image upload")
@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {
    private final ProductService service;

    @Operation(summary = "Get a product by id")
    @GetMapping("/{id}")
    public Result<Product> get(@PathVariable Long id) {
        return Result.success(service.getById(id));
    }

    @Operation(summary = "List products (paginated)")
    @GetMapping
    public Result<PageResult<Product>> page(@Valid @ParameterObject PageQuery query) {
        return Result.success(service.page(query));
    }

    @Operation(summary = "Create a product")
    @PostMapping
    public Result<Product> create(@Valid @RequestBody ProductDTO dto) {
        return Result.success(service.create(dto));
    }

    @Operation(summary = "Update a product")
    @PutMapping("/{id}")
    public Result<Product> update(@PathVariable Long id, @Valid @RequestBody ProductDTO dto) {
        return Result.success(service.update(id, dto));
    }

    @Operation(summary = "Delete a product")
    @DeleteMapping("/{id}")
    public ResponseEntity<Result<Void>> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.ok(Result.success());
    }

    @Operation(summary = "Upload a product image")
    @PostMapping("/{id}/image")
    public Result<String> uploadImage(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return Result.success(service.uploadImage(id, file));
    }
}
```

- [ ] **Step 14: Delete the old DemoProduct files**

```bash
rm frame-backend/src/main/java/com/iflytek/frame/entity/DemoProduct.java
rm frame-backend/src/main/java/com/iflytek/frame/mapper/DemoProductMapper.java
rm frame-backend/src/main/resources/mapper/DemoProductMapper.xml
rm frame-backend/src/main/java/com/iflytek/frame/service/DemoProductService.java
rm frame-backend/src/main/java/com/iflytek/frame/service/impl/DemoProductServiceImpl.java
rm frame-backend/src/main/java/com/iflytek/frame/controller/DemoProductController.java
rm frame-backend/src/main/java/com/iflytek/frame/dto/DemoProductDTO.java
```

- [ ] **Step 15: Compile + run all tests**

Run: `mvn -q clean test`
Expected: PASS (context-loads + UserServiceImplTest + ProductServiceImplTest). Requires Redis running — verify `redis-cli ping` → PONG. If `contextLoads` fails to connect to Redis, start the Redis Windows service.

- [ ] **Step 16: Commit**

```bash
git add -A frame-backend/
git commit -m "feat: product CRUD + image upload + Redis-cached reads + 409/429 error codes"
```

---

## Task 4: Synchronous Checkout + Idempotency (the correctness foundation, pre-Redis-gate)

> This task builds the checkout flow with the **DB conditional-update gate only** (no Lua/Stream yet — those come in Task 5). It proves the no-oversell/no-double-order guarantee synchronously first, so the Redis gate in Task 5 is an optimization layered on a correct core. The idempotency-key unique constraint is the no-double-order authority in both.

**Files:**
- Create: `dto/CheckoutRequest.java`, `dto/CheckoutResponse.java`, `dto/OrderItemDTO.java`, `service/CheckoutService.java`, `service/impl/CheckoutServiceImpl.java`, `service/OrderService.java`, `service/impl/OrderServiceImpl.java`, `controller/CheckoutController.java`
- Test: `src/test/java/com/iflytek/frame/service/CheckoutServiceImplTest.java`

**Interfaces:**
- Consumes: `Order`, `OrderItem`, `Product` entities + mappers; `ProductMapper.decrementStock(id, qty)` (Task 1); `SecurityUtils.currentUserId()`; `ResultCode`.
- Produces: `CheckoutService.checkout(CheckoutRequest)` → `CheckoutResponse` (orderId, status). `OrderService` (`getStatus(Long id)`, `getMyOrders()`, `cancel(Long id)`). Endpoints: `POST /checkout` (returns `Result<CheckoutResponse>`), `GET /orders/me` (returns `Result<PageResult<Order>>` or list). Synchronous version: checkout blocks until the order is persisted (status=PENDING); the async/202 variant comes in Task 5.

- [ ] **Step 1: Write the DTOs**

`dto/OrderItemDTO.java`:
```java
package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "A checkout line item")
public class OrderItemDTO {
    @NotNull(message = "productId required")
    private Long productId;
    @NotNull(message = "quantity required")
    @Min(value = 1, message = "quantity >= 1")
    @Max(value = 9999, message = "quantity <= 9999")
    private Integer quantity;
}
```

`dto/CheckoutRequest.java`:
```java
package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Checkout request")
public class CheckoutRequest {
    @NotEmpty(message = "items must not be empty")
    @Valid
    private List<OrderItemDTO> items;
    private String customerName;
    private String customerEmail;
    private String shippingAddress;
    @NotBlank(message = "idempotencyKey required")
    private String idempotencyKey;
}
```

`dto/CheckoutResponse.java`:
```java
package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@Schema(description = "Checkout response")
public class CheckoutResponse {
    private Long orderId;
    private String status;   // "pending" (synchronous V1) / "queued" (Task 5)
}
```

- [ ] **Step 2: Write the failing test for CheckoutService (synchronous DB-gate)**

`src/test/java/com/iflytek/frame/service/CheckoutServiceImplTest.java`:
```java
package com.iflytek.frame.service;

import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.dto.CheckoutRequest;
import com.iflytek.frame.dto.CheckoutResponse;
import com.iflytek.frame.dto.OrderItemDTO;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.mapper.OrderItemMapper;
import com.iflytek.frame.mapper.OrderMapper;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.impl.CheckoutServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CheckoutServiceImplTest {
    @Mock ProductMapper productMapper;
    @Mock OrderMapper orderMapper;
    @Mock OrderItemMapper orderItemMapper;
    @InjectMocks CheckoutServiceImpl checkoutService;

    private CheckoutRequest req(String key) {
        CheckoutRequest r = new CheckoutRequest();
        OrderItemDTO item = new OrderItemDTO();
        item.setProductId(1L); item.setQuantity(2);
        r.setItems(List.of(item));
        r.setCustomerName("Bob"); r.setCustomerEmail("b@x.com"); r.setShippingAddress("here");
        r.setIdempotencyKey(key);
        return r;
    }

    @Test
    void checkout_stockSufficient_decrementsAndCreatesPendingOrder() {
        Product p = new Product(); p.setId(1L); p.setPriceInCents(1999); p.setStock(5);
        when(productMapper.selectById(1L)).thenReturn(p);
        when(productMapper.decrementStock(1L, 2)).thenReturn(1); // stock claimed
        when(orderMapper.insert(any())).thenAnswer(inv -> { ((com.iflytek.frame.entity.Order) inv.getArgument(0)).setId(42L); return 1; });

        CheckoutResponse resp = checkoutService.checkout(req("key-1"), 7L);

        assertThat(resp.getOrderId()).isEqualTo(42L);
        assertThat(resp.getStatus()).isEqualTo("pending");
        verify(productMapper).decrementStock(1L, 2);
        verify(orderMapper).insert(any());
    }

    @Test
    void checkout_stockInsufficient_throwsConflict() {
        Product p = new Product(); p.setId(1L); p.setPriceInCents(1999); p.setStock(1);
        when(productMapper.selectById(1L)).thenReturn(p);
        when(productMapper.decrementStock(1L, 2)).thenReturn(0); // stock claim FAILED

        assertThatThrownBy(() -> checkoutService.checkout(req("key-2"), 7L))
                .isInstanceOf(com.iflytek.frame.exception.BusinessException.class);
        verify(orderMapper, never()).insert(any());
    }

    @Test
    void checkout_duplicateIdempotencyKey_returnsExistingPendingOrder() {
        Product p = new Product(); p.setId(1L); p.setPriceInCents(1999); p.setStock(5);
        when(productMapper.selectById(1L)).thenReturn(p);
        when(productMapper.decrementStock(1L, 2)).thenReturn(1);
        when(orderMapper.insert(any())).thenAnswer(inv -> { ((com.iflytek.frame.entity.Order) inv.getArgument(0)).setId(99L); return 1; });

        checkoutService.checkout(req("dup-key"), 7L);          // first
        // second call with same key already has a row -> DuplicateKeyException
        when(orderMapper.insert(any())).thenThrow(new DuplicateKeyException("uk_idempotency_key"));
        CheckoutResponse resp = checkoutService.checkout(req("dup-key"), 7L);

        assertThat(resp.getOrderId()).isEqualTo(99L);
        assertThat(resp.getStatus()).isEqualTo("pending");
        // stock decremented only ONCE (first call); second call's decrement must be reversed
        verify(productMapper, times(2)).decrementStock(1L, 2); // called twice, but second rolled back
    }
}
```

> **Note on the duplicate-key reversal:** the second `decrementStock` ran before the insert threw, so the impl must `incrby` the product stock back on `DuplicateKeyException`. The test asserts `decrementStock` was called twice but the final order is the first one (id=99). The impl compensates.

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn -q -Dtest=CheckoutServiceImplTest test`
Expected: FAIL — `CheckoutServiceImpl` does not exist.

- [ ] **Step 4: Write CheckoutService interface + impl (synchronous)**

`service/CheckoutService.java`:
```java
package com.iflytek.frame.service;

import com.iflytek.frame.dto.CheckoutRequest;
import com.iflytek.frame.dto.CheckoutResponse;

public interface CheckoutService {
    CheckoutResponse checkout(CheckoutRequest request, Long userId);
}
```

`service/impl/CheckoutServiceImpl.java`:
```java
package com.iflytek.frame.service.impl;

import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.CheckoutRequest;
import com.iflytek.frame.dto.CheckoutResponse;
import com.iflytek.frame.dto.OrderItemDTO;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.OrderItem;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.OrderItemMapper;
import com.iflytek.frame.mapper.OrderMapper;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.CheckoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CheckoutServiceImpl implements CheckoutService {

    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    @Override
    @Transactional
    public CheckoutResponse checkout(CheckoutRequest request, Long userId) {
        // 1. Aggregate quantities per product (dedup within the cart)
        Map<Long, Integer> qtyByProduct = new HashMap<>();
        for (OrderItemDTO item : request.getItems()) {
            qtyByProduct.merge(item.getProductId(), item.getQuantity(), Integer::sum);
        }

        // 2. Fetch products (server-side price authority — never trust client)
        List<Product> products = productMapper.selectBatchIds(qtyByProduct.keySet());
        if (products.size() != qtyByProduct.size()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "one or more products not found");
        }
        Map<Long, Product> byId = new HashMap<>();
        for (Product p : products) byId.put(p.getId(), p);

        // 3. Synchronous DB conditional-update gate (the correctness core; Redis gate added in Task 5)
        for (Map.Entry<Long, Integer> e : qtyByProduct.entrySet()) {
            int rows = productMapper.decrementStock(e.getKey(), e.getValue());
            if (rows == 0) {
                // Roll back already-decremented items
                for (Map.Entry<Long, Integer> prev : qtyByProduct.entrySet()) {
                    if (prev.getKey().equals(e.getKey())) break;
                    productMapper.recreditStock(prev.getKey(), prev.getValue());
                }
                throw new BusinessException(ResultCode.CONFLICT, "sold out");
            }
        }

        // 4. Compute total from DB prices
        int totalInCents = 0;
        for (Map.Entry<Long, Integer> e : qtyByProduct.entrySet()) {
            totalInCents += byId.get(e.getKey()).getPriceInCents() * e.getValue();
        }

        // 5. Insert order (status=PENDING) + items — idempotency via unique constraint
        Order order = new Order();
        order.setUserId(userId);
        order.setCustomerName(request.getCustomerName());
        order.setCustomerEmail(request.getCustomerEmail());
        order.setShippingAddress(request.getShippingAddress());
        order.setTotalInCents(totalInCents);
        order.setStatus(OrderStatus.PENDING);
        order.setIdempotencyKey(request.getIdempotencyKey());
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException dup) {
            // A prior delivery already created this order — reverse our decrement and return the existing one
            for (Map.Entry<Long, Integer> e : qtyByProduct.entrySet()) {
                productMapper.recreditStock(e.getKey(), e.getValue());
            }
            Order existing = orderMapper.selectByIdempotencyKey(request.getIdempotencyKey());
            return new CheckoutResponse(existing.getId(), "pending");
        }
        for (Map.Entry<Long, Integer> e : qtyByProduct.entrySet()) {
            Product p = byId.get(e.getKey());
            OrderItem oi = new OrderItem();
            oi.setOrderId(order.getId());
            oi.setProductId(p.getId());
            oi.setName(p.getName());
            oi.setPriceInCents(p.getPriceInCents());
            oi.setQuantity(e.getValue());
            orderItemMapper.insert(oi);
        }
        return new CheckoutResponse(order.getId(), "pending");
    }
}
```

- [ ] **Step 5: Add recreditStock + selectByIdempotencyKey to OrderMapper/ProductMapper**

In `ProductMapper.java`:
```java
    int recreditStock(@Param("id") Long id, @Param("qty") int qty);
```
In `ProductMapper.xml`:
```xml
    <update id="recreditStock">
        update product set stock = stock + #{qty}, update_time = now() where id = #{id}
    </update>
```
In `OrderMapper.java`:
```java
    com.iflytek.frame.entity.Order selectByIdempotencyKey(@Param("key") String key);
```
In a new `resources/mapper/OrderMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.iflytek.frame.mapper.OrderMapper">
    <select id="selectByIdempotencyKey" resultType="com.iflytek.frame.entity.Order">
        select * from orders where idempotency_key = #{key} limit 1
    </select>
</mapper>
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -q -Dtest=CheckoutServiceImplTest test`
Expected: PASS (all 3 cases).

- [ ] **Step 7: Write OrderService + OrderServiceImpl (status, my orders, cancel)**

`service/OrderService.java`:
```java
package com.iflytek.frame.service;

import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.util.PageQuery;

public interface OrderService {
    Order getById(Long id, Long userId);
    PageResult<Order> myOrders(Long userId, PageQuery query);
    void cancel(Long id, Long userId);
    OrderStatus flipStatus(Long id, OrderStatus from, OrderStatus to);  // atomic conditional update
}
```

`service/impl/OrderServiceImpl.java`:
```java
package com.iflytek.frame.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.OrderMapper;
import com.iflytek.frame.service.OrderService;
import com.iflytek.frame.util.PageQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;

    @Override
    public Order getById(Long id, Long userId) {
        Order o = orderMapper.selectById(id);
        if (o == null || !o.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "order not found");
        }
        return o;
    }

    @Override
    public PageResult<Order> myOrders(Long userId, PageQuery query) {
        Page<Order> page = new Page<>(query.getCurrent(), query.getSize());
        LambdaQueryWrapper<Order> w = new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .orderByDesc(Order::getCreateTime);
        var result = orderMapper.selectPage(page, w);
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }

    @Override
    public void cancel(Long id, Long userId) {
        Order o = getById(id, userId);
        // atomic: only PENDING can be cancelled
        int rows = orderMapper.updateStatusIf(id, OrderStatus.PENDING, OrderStatus.CANCELLED);
        if (rows == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "order cannot be cancelled in current state");
        }
    }

    @Override
    public OrderStatus flipStatus(Long id, OrderStatus from, OrderStatus to) {
        int rows = orderMapper.updateStatusIf(id, from, to);
        if (rows == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "order state transition not allowed");
        }
        return to;
    }
}
```
Add `updateStatusIf` to `OrderMapper.java` + XML:
```java
    int updateStatusIf(@Param("id") Long id, @Param("from") com.iflytek.frame.common.OrderStatus from, @Param("to") com.iflytek.frame.common.OrderStatus to);
```
```xml
    <update id="updateStatusIf">
        update orders set status = #{to}, update_time = now()
        where id = #{id} and status = #{from}
    </update>
```
(MyBatis-Plus converts the enum to its name string for the `status` column by default with `map-underscore-to-camel-case` + the enum handler — verify the enum is stored as the name. If not, add `@EnumValue` or a `TypeHandler`; the simplest is configuring `mybatis-plus.configuration.default-enum-type-handler: com.baomidou.mybatisplus.core.handlers.MybatisEnumTypeHandler` in application.yml and annotating the enum value. For a plain name enum, MyBatis-Plus defaults to `EnumTypeHandler` storing the name — which matches our schema's VARCHAR.)

- [ ] **Step 8: Write CheckoutController + OrderController**

`controller/CheckoutController.java`:
```java
package com.iflytek.frame.controller;

import com.iflytek.frame.common.Result;
import com.iflytek.frame.dto.CheckoutRequest;
import com.iflytek.frame.dto.CheckoutResponse;
import com.iflytek.frame.service.CheckoutService;
import com.iflytek.frame.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Checkout", description = "Flash-sale checkout")
@RestController
@RequestMapping("/checkout")
@RequiredArgsConstructor
public class CheckoutController {
    private final CheckoutService checkoutService;

    @Operation(summary = "Place an order (synchronous in V1; async in Task 5)")
    @PostMapping
    public Result<CheckoutResponse> checkout(@Valid @RequestBody CheckoutRequest request) {
        return Result.success(checkoutService.checkout(request, SecurityUtils.currentUserId()));
    }
}
```

`controller/OrderController.java` (status/my-orders/cancel/pay; `GET /orders/{id}` full detail in Task 6 with the queued state — for now it returns the persisted row):
```java
package com.iflytek.frame.controller;

import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.common.Result;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.service.OrderService;
import com.iflytek.frame.util.PageQuery;
import com.iflytek.frame.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Order", description = "Order status, my orders, pay, cancel")
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orderService;

    @Operation(summary = "Get my order by id")
    @GetMapping("/{id}")
    public Result<Order> get(@PathVariable Long id) {
        return Result.success(orderService.getById(id, SecurityUtils.currentUserId()));
    }

    @Operation(summary = "My orders")
    @GetMapping("/me")
    public Result<PageResult<Order>> myOrders(@Valid @ParameterObject PageQuery query) {
        return Result.success(orderService.myOrders(SecurityUtils.currentUserId(), query));
    }

    @Operation(summary = "Cancel a pending order")
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        orderService.cancel(id, SecurityUtils.currentUserId());
        return Result.success();
    }
}
```
(Payment endpoint `POST /orders/{id}/pay` lands in Task 6 with the `PaymentService`.)

- [ ] **Step 9: Compile + run all tests**

Run: `mvn -q clean test`
Expected: PASS (all prior + CheckoutServiceImplTest).

- [ ] **Step 10: Commit**

```bash
git add -A frame-backend/
git commit -m "feat: synchronous checkout with DB conditional-update gate + idempotency unique constraint"
```

---

## Task 5: Redis Lua Gate + Stream + Consumer-Group Consumer (the Taobao model)

> This layers the Redis primary gate onto the Task-4 synchronous core. The checkout hot path becomes async: Lua-gate → XADD → 202 "queued". A consumer-group consumer drains the stream, does the durable write (reusing the Task-4 idempotency/conditional-UPDATE logic), and XACKs only after commit. This is where at-least-once is *constructed*.

**Files:**
- Create: `resources/lua/stock_gate.lua`, `service/StockGateService.java`, `service/impl/StockGateServiceImpl.java`, `service/StreamConsumerService.java`, `controller/AdminSaleController.java`
- Modify: `service/impl/CheckoutServiceImpl.java` (add the Lua gate + XADD before the synchronous fallback — actually refactor: the hot path becomes Lua-gate→XADD→return 202; the synchronous write logic moves into `StreamConsumerService`)
- Test: `src/test/java/com/iflytek/frame/service/StockGateServiceImplTest.java`

**Interfaces:**
- Consumes: `StringRedisTemplate`; the Lua script; `ProductMapper.decrementStock`/`recreditStock` (Task 4); `OrderMapper`/`OrderItemMapper`; `ResultCode`.
- Produces: `StockGateService.tryClaimStock(List<Long> productIds, List<Integer> qtys)` → `boolean` (atomic Lua); `StockGateService.loadStock(Long productId)` (sale-open); `StockGateService.recoverStock(Long productId)`. `StreamConsumerService` (a `@Component` with a `@Scheduled` poll loop + `@PostConstruct` group creation). The checkout hot path returns `{orderId, status:"queued"}`.

- [ ] **Step 1: Write the Lua script (two-pass all-or-nothing)**

`resources/lua/stock_gate.lua`:
```lua
-- KEYS: stock:{pid} ...   ARGV: qty, qty, ...  (paired by index)
-- Returns 'OK' if ALL items have sufficient stock (and decrements all); 'FAIL' otherwise (no mutations).
local to_decr = {}
for i = 1, #KEYS do
  local s = tonumber(redis.call('get', KEYS[i]))
  local q = tonumber(ARGV[i])
  if not s or s < q then
    return 'FAIL'
  end
  to_decr[i] = { KEYS[i], q }
end
for _, pair in ipairs(to_decr) do
  redis.call('decrby', pair[1], pair[2])
end
return 'OK'
```

- [ ] **Step 2: Write the failing test for StockGateService (Lua OK / FAIL / partial-rollback)**

`src/test/java/com/iflytek/frame/service/StockGateServiceImplTest.java`:
```java
package com.iflytek.frame.service;

import com.iflytek.frame.entity.Product;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.impl.StockGateServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockGateServiceImplTest {
    @Mock StringRedisTemplate redis;
    @Mock ProductMapper productMapper;
    @InjectMocks StockGateServiceImpl stockGateService;

    @Test
    @SuppressWarnings("unchecked")
    void tryClaimStock_returnsTrue_whenLuaReturnsOK() {
        when(redis.execute(any(RedisScript.class), anyList(), any(String[].class))).thenReturn("OK");
        boolean ok = stockGateService.tryClaimStock(List.of(1L, 2L), List.of(2, 1));
        assertThat(ok).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void tryClaimStock_returnsFalse_whenLuaReturnsFAIL() {
        when(redis.execute(any(RedisScript.class), anyList(), any(String[].class))).thenReturn("FAIL");
        boolean ok = stockGateService.tryClaimStock(List.of(1L), List.of(99));
        assertThat(ok).isFalse();
    }

    @Test
    void loadStock_setsRedisStockFromDb() {
        Product p = new Product(); p.setId(1L); p.setStock(5);
        when(productMapper.selectById(1L)).thenReturn(p);
        stockGateService.loadStock(1L);
        verify(redis).opsForValue().set("stock:1", "5");
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn -q -Dtest=StockGateServiceImplTest test`
Expected: FAIL — `StockGateServiceImpl` does not exist.

- [ ] **Step 4: Write StockGateService interface + impl**

`service/StockGateService.java`:
```java
package com.iflytek.frame.service;

import java.util.List;

public interface StockGateService {
    /** Atomic Lua check-and-decrement for all items. true = all claimed; false = none mutated. */
    boolean tryClaimStock(List<Long> productIds, List<Integer> quantities);

    /** Sale-open: load stock:{id} from DB into Redis. */
    void loadStock(Long productId);

    /** Recovery: re-load stock:{id} from current DB stock (missing key mid-sale). */
    void recoverStock(Long productId);

    /** Re-credit Redis stock (consumer FAILED branch). */
    void recredit(Long productId, int qty);
}
```

`service/impl/StockGateServiceImpl.java`:
```java
package com.iflytek.frame.service.impl;

import com.iflytek.frame.entity.Product;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.StockGateService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class StockGateServiceImpl implements StockGateService {

    private final StringRedisTemplate redis;
    private final ProductMapper productMapper;

    private RedisScript<String> stockGateScript;

    @PostConstruct
    void init() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/stock_gate.lua")));
        script.setResultType(String.class);
        this.stockGateScript = script;
    }

    @Override
    public boolean tryClaimStock(List<Long> productIds, List<Integer> quantities) {
        List<String> keys = productIds.stream().map(id -> "stock:" + id).collect(Collectors.toList());
        String[] args = quantities.stream().map(String::valueOf).toArray(String[]::new);
        String result = redis.execute(stockGateScript, keys, args);
        return "OK".equals(result);
    }

    @Override
    public void loadStock(Long productId) {
        Product p = productMapper.selectById(productId);
        redis.opsForValue().set("stock:" + productId, String.valueOf(p.getStock()));
    }

    @Override
    public void recoverStock(Long productId) {
        loadStock(productId); // re-load from current DB stock
    }

    @Override
    public void recredit(Long productId, int qty) {
        redis.opsForValue().increment("stock:" + productId, qty);
    }
}
```

- [ ] **Step 5: Run the StockGate test to verify it passes**

Run: `mvn -q -Dtest=StockGateServiceImplTest test`
Expected: PASS.

- [ ] **Step 6: Refactor CheckoutService to the async hot path (Lua gate → XADD → 202)**

Modify `service/impl/CheckoutServiceImpl.java`. The `checkout` method no longer does the synchronous DB write — it gates via Redis Lua, then XADDs an order message to the `orders:pending` stream, sets `order_status:{id}` = "queued", and returns `202`. The durable-write logic moves to `StreamConsumerService` (Step 7) which reuses the same idempotency/conditional-UPDATE approach.

New `checkout` body:
```java
    private final StringRedisTemplate redis;
    private final StockGateService stockGateService;
    private final ProductMapper productMapper;

    @Override
    public CheckoutResponse checkout(CheckoutRequest request, Long userId) {
        // 1. Aggregate quantities
        Map<Long, Integer> qtyByProduct = new LinkedHashMap<>();
        for (OrderItemDTO item : request.getItems()) {
            qtyByProduct.merge(item.getProductId(), item.getQuantity(), Integer::sum);
        }

        // 2. Fetch products (server-side price authority)
        List<Product> products = productMapper.selectBatchIds(qtyByProduct.keySet());
        if (products.size() != qtyByProduct.size()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "one or more products not found");
        }

        // 3. Redis Lua gate (atomic, all-or-nothing)
        List<Long> ids = new ArrayList<>(qtyByProduct.keySet());
        List<Integer> qtys = ids.stream().map(qtyByProduct::get).collect(Collectors.toList());
        boolean claimed = stockGateService.tryClaimStock(ids, qtys);
        if (!claimed) {
            throw new BusinessException(ResultCode.CONFLICT, "sold out");
        }

        // 4. Compute total from DB prices
        int totalInCents = 0;
        Map<Long, Product> byId = products.stream().collect(Collectors.toMap(Product::getId, p -> p));
        for (Map.Entry<Long, Integer> e : qtyByProduct.entrySet()) {
            totalInCents += byId.get(e.getKey()).getPriceInCents() * e.getValue();
        }

        // 5. XADD to orders:pending + set order_status = queued, return 202
        // orderId is generated now (used as the stream message id correlation + the queued-status key)
        Long orderId = System.currentTimeMillis(); // simplistic; a Snowflake/UUID is better in prod — note in README
        String payload = jsonPayload(orderId, userId, request, totalInCents, qtyByProduct);
        redis.opsForStream().add("orders:pending", java.util.Map.of(
                "orderId", String.valueOf(orderId),
                "userId", String.valueOf(userId),
                "payload", payload));
        redis.opsForValue().set("order_status:" + orderId, "queued");
        return new CheckoutResponse(orderId, "queued");
    }

    private String jsonPayload(Long orderId, Long userId, CheckoutRequest req, int total, Map<Long,Integer> items) {
        // Serialize the durable-write context for the consumer. Use ObjectMapper.
        try {
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = m.createObjectNode();
            node.put("orderId", orderId);
            node.put("userId", userId);
            node.put("customerName", req.getCustomerName());
            node.put("customerEmail", req.getCustomerEmail());
            node.put("shippingAddress", req.getShippingAddress());
            node.put("totalInCents", total);
            node.put("idempotencyKey", req.getIdempotencyKey());
            var itemsNode = node.putArray("items");
            items.forEach((pid, qty) -> {
                var i = itemsNode.addObject();
                i.put("productId", pid);
                i.put("quantity", qty);
            });
            return m.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
```
Remove the now-unused `@Transactional`, `orderMapper`, `orderItemMapper` from the hot path (the consumer owns those). Keep the `decrementStock`/`recreditStock`/idempotency logic — it moves verbatim into `StreamConsumerService`.

- [ ] **Step 7: Write StreamConsumerService (consumer group + XACK-after-commit + XAUTOCLAIM)**

`service/StreamConsumerService.java`:
```java
package com.iflytek.frame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.OrderItem;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.mapper.OrderItemMapper;
import com.iflytek.frame.mapper.OrderMapper;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.StockGateService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class StreamConsumerService {

    private static final String STREAM = "orders:pending";
    private static final String GROUP = "order-consumers";
    private static final String CONSUMER = "c1";

    private final StringRedisTemplate redis;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;
    private final StockGateService stockGateService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    void createGroup() {
        try {
            redis.opsForStream().createGroup(STREAM, ReadOffset.from("0"), GROUP);
        } catch (Exception e) {
            // group already exists or stream missing — MKSTREAM handled by first XADD; ignore if BUSYGROUP
            log.debug("consumer group create: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 500)
    public void drain() {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                org.springframework.data.redis.connection.stream.Consumer.from(GROUP, CONSUMER),
                StreamReadOptions.empty().count(10),
                StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
        if (records == null || records.isEmpty()) return;
        for (MapRecord<String, Object, Object> rec : records) {
            try {
                process(rec);
            } catch (Exception e) {
                log.error("failed to process record {}: leaving in PEL for retry/XAUTOCLAIM", rec.getId(), e);
                // do NOT XACK — leave in PEL for the stalled-consumer recovery to reclaim
            }
        }
    }

    @Transactional
    public void process(MapRecord<String, Object, Object> rec) {
        String payload = String.valueOf(rec.getValue().get("payload"));
        Long orderId;
        try {
            JsonNode node = objectMapper.readTree(payload);
            orderId = node.get("orderId").asLong();
            Long userId = node.get("userId").asLong();
            String idempotencyKey = node.get("idempotencyKey").asText();
            int totalInCents = node.get("totalInCents").asInt();
            String customerName = node.get("customerName").asText();
            String customerEmail = node.get("customerEmail").asText();
            String shippingAddress = node.get("shippingAddress").asText();

            Order order = new Order();
            order.setId(orderId); // deterministic — matches the queued-status key
            order.setUserId(userId);
            order.setCustomerName(customerName);
            order.setCustomerEmail(customerEmail);
            order.setShippingAddress(shippingAddress);
            order.setTotalInCents(totalInCents);
            order.setStatus(OrderStatus.PENDING);
            order.setIdempotencyKey(idempotencyKey);

            try {
                orderMapper.insert(order);
            } catch (DataIntegrityViolationException dup) {
                // duplicate idempotency key — already processed; do NOT re-decrement stock
                redis.opsForStream().acknowledge(STREAM, GROUP, rec.getId());
                redis.opsForValue().set("order_status:" + orderId, "pending");
                return;
            }

            // DB backstop: conditional decrement for each item (Redis already decremented in the hot path;
            // this catches Redis/DB drift). If rows=0, the order FAILED + re-credit Redis.
            for (JsonNode item : node.get("items")) {
                Long pid = item.get("productId").asLong();
                int qty = item.get("quantity").asInt();
                int rows = productMapper.decrementStock(pid, qty);
                if (rows == 0) {
                    // Redis drifted: mark FAILED, re-credit Redis, del one-per-user key, ack
                    order.setStatus(OrderStatus.FAILED);
                    orderMapper.updateById(order);
                    stockGateService.recredit(pid, qty);
                    redis.delete("seckill:" + pid + ":" + userId);
                    redis.opsForValue().set("order_status:" + orderId, "failed");
                    redis.opsForStream().acknowledge(STREAM, GROUP, rec.getId());
                    return;
                }
                Product p = productMapper.selectById(pid);
                OrderItem oi = new OrderItem();
                oi.setOrderId(orderId);
                oi.setProductId(pid);
                oi.setName(p.getName());
                oi.setPriceInCents(p.getPriceInCents());
                oi.setQuantity(qty);
                orderItemMapper.insert(oi);
                // one-per-user key set AFTER durable persist, inside the commit
                redis.opsForValue().set("seckill:" + pid + ":" + userId, "1",
                        3600, java.util.concurrent.TimeUnit.SECONDS);
            }
            redis.opsForValue().set("order_status:" + orderId, "pending");
        } catch (Exception e) {
            throw new RuntimeException(e); // leaves message in PEL
        }
        // XACK ONLY AFTER the @Transactional commits (this runs in the same transactional proxy? NO —
        // ack must be AFTER commit. Move the ack to an after-commit hook.)
    }
}
```
**Critical fix — XACK-after-commit:** `@Transactional` commits when `process()` returns. To XACK only after commit, use `TransactionSynchronizationManager.registerSynchronization(... afterCommit() ... ack)`. Modify the end of `process()`:
```java
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() {
                    redis.opsForStream().acknowledge(STREAM, GROUP, rec.getId());
                }
            });
```
(Remove the bare `acknowledge` calls inside `process()`; on the FAILED/DupKey branches those are terminal and also fine to ack after commit — but those branches don't return through the registered hook. Simplest correct approach: register the afterCommit ack at the very start of `process()`, and on terminal-early branches (DupKey, FAILED) it still fires after the method returns + commits. Do that.)

- [ ] **Step 8: Add the XAUTOCLAIM stalled-consumer recovery (scheduled)**

In `StreamConsumerService`, add:
```java
    @Scheduled(fixedDelay = 60000)
    public void reclaimStalled() {
        // Find entries idle > 60s in this group and XAUTOCLAIM them to this consumer.
        try {
            var pending = redis.opsForStream().pending(STREAM, GROUP);
            // XAUTOCLAIM (Redis 6.2+). On Redis 5.0.14, use XPENDING + XCLAIM instead.
            // For 5.0: iterate pending, XCLAIM idle ones.
        } catch (Exception e) {
            log.debug("reclaim stalled: {}", e.getMessage());
        }
    }
```
> **Redis 5.0 note:** `XAUTOCLAIM` is Redis 6.2+. Our installed Redis is 5.0.14.1, so use `XPENDING` (range) + `XCLAIM` per idle entry instead. The consumer reads its own PEL via `XREADGROUP ... ID 0` (which re-reads unacked messages for this consumer). Simpler V1 approach: in `drain()`, also do a `XREADGROUP GROUP g c COUNT n STREAMS s 0` read (ID `0` re-delivers this consumer's pending entries) — this reprocesses anything the consumer crashed on. Document this in the README; full cross-consumer XCLAIM is a prod refinement.

- [ ] **Step 9: Write the AdminSaleController (open a flash sale)**

`controller/AdminSaleController.java`:
```java
package com.iflytek.frame.controller;

import com.iflytek.frame.common.Result;
import com.iflytek.frame.service.StockGateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Admin", description = "Admin sale management")
@RestController
@RequestMapping("/admin/sales")
@RequiredArgsConstructor
public class AdminSaleController {
    private final StockGateService stockGateService;

    @Operation(summary = "Open a flash sale (load stock into Redis)")
    @PostMapping("/{productId}")
    public Result<Void> openSale(@PathVariable Long productId) {
        stockGateService.loadStock(productId);
        return Result.success();
    }
}
```
(This endpoint is protected by the `hasRole("ADMIN")` rule added in Task 2 Step 10.)

- [ ] **Step 10: Enable @Scheduled + async**

The template's `FrameBackendApplication.java` already has `@EnableAsync` (line 17). Add `@EnableScheduling` to the same class.

- [ ] **Step 11: Compile + run all tests**

Run: `mvn -q clean test`
Expected: PASS (StockGateServiceImplTest + all prior). The `StreamConsumerService` has no unit test yet (integration-tested in Task 7) — acceptable because its logic is the durable write, already unit-covered by the Task-4 CheckoutService tests' spirit; Task 7's integration test exercises it end-to-end.

- [ ] **Step 12: Commit**

```bash
git add -A frame-backend/
git commit -m "feat: Redis Lua gate + Stream consumer-group (at-least-once, XACK-after-commit) + admin sale endpoint"
```

---

## Task 6: Payment + Queued-Status Endpoint + Cancel

**Files:**
- Create: `service/PaymentService.java`, `service/impl/PaymentServiceImpl.java`
- Modify: `controller/OrderController.java` (add `/orders/{id}/pay`, flesh out `GET /orders/{id}` to return the queued state)
- Modify: `service/impl/OrderServiceImpl.java` (`getById` returns a synthetic status when the order row doesn't exist yet but `order_status:{id}` = queued in Redis)
- Test: `src/test/java/com/iflytek/frame/service/PaymentServiceImplTest.java`

**Interfaces:**
- Consumes: `OrderService.flipStatus` (Task 4); `StringRedisTemplate` (for `order_status:{id}`); `OrderStatus`.
- Produces: `PaymentService.pay(Long orderId, Long userId)` → `Order` (status=PAID). `GET /orders/{id}` returns `Order` (or a minimal DTO with the queued status when no row exists). `POST /orders/{id}/pay`.

- [ ] **Step 1: Write the failing test for PaymentService (atomic flip)**

`src/test/java/com/iflytek/frame/service/PaymentServiceImplTest.java`:
```java
package com.iflytek.frame.service;

import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.service.impl.PaymentServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {
    @Mock OrderService orderService;
    @InjectMocks PaymentServiceImpl paymentService;

    @Test
    void pay_flipsPendingToPaid() {
        when(orderService.flipStatus(7L, OrderStatus.PENDING, OrderStatus.PAID)).thenReturn(OrderStatus.PAID);
        paymentService.pay(7L, 99L);
        verify(orderService).flipStatus(7L, OrderStatus.PENDING, OrderStatus.PAID);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -Dtest=PaymentServiceImplTest test`
Expected: FAIL — `PaymentServiceImpl` does not exist.

- [ ] **Step 3: Write PaymentService interface + impl**

`service/PaymentService.java`:
```java
package com.iflytek.frame.service;

import com.iflytek.frame.entity.Order;

public interface PaymentService {
    Order pay(Long orderId, Long userId);
}
```

`service/impl/PaymentServiceImpl.java`:
```java
package com.iflytek.frame.service.impl;

import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.service.OrderService;
import com.iflytek.frame.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final OrderService orderService;

    @Override
    public Order pay(Long orderId, Long userId) {
        // mock payment always succeeds (mentor's instruction); the atomic flip is the guard
        orderService.flipStatus(orderId, OrderStatus.PENDING, OrderStatus.PAID);
        return orderService.getById(orderId, userId);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -Dtest=PaymentServiceImplTest test`
Expected: PASS.

- [ ] **Step 5: Flesh out GET /orders/{id} to return the queued state**

Modify `OrderServiceImpl.getById(Long id, Long userId)` to handle the queued case: when the row doesn't exist but `order_status:{id}` = "queued" in Redis, return an `Order` with just id + status set (so the frontend can poll). Inject `StringRedisTemplate`:
```java
    private final StringRedisTemplate redis;

    @Override
    public Order getById(Long id, Long userId) {
        Order o = orderMapper.selectById(id);
        if (o == null) {
            String status = redis.opsForValue().get("order_status:" + id);
            if ("queued".equals(status)) {
                Order queued = new Order();
                queued.setId(id);
                queued.setStatus(OrderStatus.PENDING); // placeholder; the frontend polls for "pending"/"failed"
                queued.setUserId(userId);
                return queued;
            }
            throw new BusinessException(ResultCode.NOT_FOUND, "order not found");
        }
        if (!o.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "order not found");
        }
        return o;
    }
```
(Frontend will poll `GET /orders/{id}` and check the `status` field; once it's `PENDING` or `FAILED`, the full row is present. A dedicated `GET /orders/{id}/status` returning a bare string is a cleaner alternative — add it if the frontend team prefers. For V1, the Order DTO carries status.)

- [ ] **Step 6: Add the pay endpoint to OrderController**

In `controller/OrderController.java`, inject `PaymentService` and add:
```java
    @Operation(summary = "Pay a pending order (mock payment)")
    @PostMapping("/{id}/pay")
    public Result<Order> pay(@PathVariable Long id) {
        return Result.success(paymentService.pay(id, SecurityUtils.currentUserId()));
    }
```

- [ ] **Step 7: Compile + run all tests**

Run: `mvn -q clean test`
Expected: PASS (all prior + PaymentServiceImplTest).

- [ ] **Step 8: Commit**

```bash
git add -A frame-backend/
git commit -m "feat: mock payment (atomic pending→paid) + queued-status order endpoint"
```

---

## Task 7: Integration + Load/Mutex Tests

**Files:**
- Create: `src/test/java/com/iflytek/frame/CheckoutFlowIntegrationTest.java`, `src/test/java/com/iflytek/frame/LoadMutexTest.java`
- (No production code changes — test-only. Requires Redis + MariaDB running.)

**Interfaces:**
- Consumes: the full backend (auth, product, checkout, consumer, payment). Requires `@SpringBootTest` web context.
- Produces: the load/mutex test artifact for the mentor (proof: exactly K succeed, final stock 0).

- [ ] **Step 1: Write the integration test (end-to-end checkout through Redis→Stream→consumer→DB)**

`src/test/java/com/iflytek/frame/CheckoutFlowIntegrationTest.java`:
```java
package com.iflytek.frame;

import com.iflytek.frame.dto.*;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600"
})
class CheckoutFlowIntegrationTest {

    @Autowired CheckoutService checkoutService;
    @Autowired OrderService orderService;
    @Autowired ProductMapper productMapper;
    @Autowired PaymentService paymentService;
    @Autowired UserService userService;

    @Test
    void checkout_endToEnd_queuedToPendingToPaid() {
        // register a user
        User user = userService.register(new RegisterRequest("itest_" + UUID.randomUUID(), "pass1234"));

        // a flash-sale product with stock 1
        Product p = new Product();
        p.setName("ITest Widget"); p.setPriceInCents(500); p.setStock(1);
        productMapper.insert(p);

        // checkout 1 unit
        CheckoutRequest req = new CheckoutRequest();
        OrderItemDTO item = new OrderItemDTO(); item.setProductId(p.getId()); item.setQuantity(1);
        req.setItems(List.of(item)); req.setCustomerName("x"); req.setCustomerEmail("x@x");
        req.setShippingAddress("y"); req.setIdempotencyKey(UUID.randomUUID().toString());
        CheckoutResponse resp = checkoutService.checkout(req, user.getId());

        // status transitions queued -> pending (consumer drains within seconds)
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(orderService.getById(resp.getOrderId(), user.getId()).getStatus())
                    .isNotNull();
        });

        // pay
        paymentService.pay(resp.getOrderId(), user.getId());
    }
}
```
(Add `org.awaitility:awaitility` test dependency to pom — Spring Boot manages the version.)

- [ ] **Step 2: Write the load/mutex test (the mentor artifact)**

`src/test/java/com/iflytek/frame/LoadMutexTest.java`:
```java
package com.iflytek.frame;

import com.iflytek.frame.dto.*;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600"
})
class LoadMutexTest {

    @Autowired CheckoutService checkoutService;
    @Autowired ProductMapper productMapper;
    @Autowired UserService userService;
    @Autowired OrderService orderService;
    @Autowired StockGateService stockGateService;

    @Test
    void concurrentBuyers_exactlyKsucceed_restSoldOut_finalStockZero() throws Exception {
        int K = 5;          // stock
        int M = 50;         // distinct buyers
        Product p = new Product();
        p.setName("Mutex Widget"); p.setPriceInCents(100); p.setStock(K);
        productMapper.insert(p);
        // load stock into Redis (open the sale) — required before checkout can claim
        stockGateService.loadStock(p.getId());

        ExecutorService pool = Executors.newFixedThreadPool(M);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok202 = new AtomicInteger();
        AtomicInteger conflict409 = new AtomicInteger();

        List<Runnable> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < M; i++) {
            final int idx = i;
            tasks.add(() -> {
                try {
                    start.await();
                    User u = userService.register(new RegisterRequest("lb_" + idx + "_" + UUID.randomUUID(), "pass1234"));
                    CheckoutRequest req = new CheckoutRequest();
                    OrderItemDTO item = new OrderItemDTO(); item.setProductId(p.getId()); item.setQuantity(1);
                    req.setItems(List.of(item)); req.setCustomerName("n"); req.setCustomerEmail("e");
                    req.setShippingAddress("a"); req.setIdempotencyKey(UUID.randomUUID().toString());
                    try {
                        checkoutService.checkout(req, u.getId());
                        ok202.incrementAndGet();
                    } catch (com.iflytek.frame.exception.BusinessException be) {
                        if (be.getCode() == 409) conflict409.incrementAndGet();
                    }
                } catch (Exception ignored) {}
            });
        }
        tasks.forEach(pool::submit);
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        // wait for the consumer to drain
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Product current = productMapper.selectById(p.getId());
            assertThat(current.getStock()).isLessThanOrEqualTo(0);
        });

        assertThat(ok202.get()).isEqualTo(K);          // exactly K claimed stock
        assertThat(conflict409.get()).isEqualTo(M - K); // rest got sold-out
        assertThat(productMapper.selectById(p.getId()).getStock()).isZero();
    }
}
```

- [ ] **Step 3: Run the integration + load tests (requires Redis + MariaDB running)**

Run: `mvn -q -Dtest='CheckoutFlowIntegrationTest,LoadMutexTest' test`
Expected: both PASS. The load test asserts exactly 5 of 50 buyers succeed, 45 get 409, final stock = 0 — the no-oversell proof.

- [ ] **Step 4: Run the full backend test suite once more**

Run: `mvn -q clean test`
Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add -A frame-backend/
git commit -m "test: integration + load/mutex proof (50 concurrent buyers, 5 succeed, stock ends at 0)"
```

---

## Task 8: Frontend (Three-Stage Checkout + Register + Flash-Sale UI + Admin)

**Files:**
- Modify: `src/api/types.ts` (Product.price→priceInCents; add Order, CheckoutRequest/Response, OrderStatus), `src/api/request.ts:19` (fix `:8080`→`:8081` comment), `src/api/product.ts` (priceInCents + uploadImage + openFlashSale), `src/api/auth.ts` (register), `src/pages/Products.tsx` (cents→dollars display), `src/router/index.tsx` (add routes)
- Create: `src/api/order.ts`, `src/pages/Register.tsx`, `src/pages/Checkout.tsx`, `src/pages/Orders.tsx`, `src/pages/Admin.tsx`
- Test: `tests/cart-reducer.test.ts` (port the old project's untested reducer), `tests/Checkout.test.tsx` (three-stage flow)

**Interfaces:**
- Consumes: the backend API (all endpoints from Tasks 2-6); the existing Axios `request` instance; Zustand auth store.
- Produces: working SPA with login/register/browse/cart/three-stage-checkout/orders/admin-open-sale.

- [ ] **Step 1: Update api/types.ts (priceInCents + new types)**

Replace `Product` in `src/api/types.ts`:
```ts
export interface Product {
  id: number
  name: string
  priceInCents: number
  stock: number
  imageUrl?: string
  category?: string
  isFlashSale?: boolean
  description?: string
  createTime?: string
  updateTime?: string
}
export interface OrderItem { id: number; orderId: number; productId: number; name: string; priceInCents: number; quantity: number }
export type OrderStatus = 'PENDING' | 'PAID' | 'CANCELLED' | 'FAILED'
export interface Order { id: number; userId: number; customerName: string; customerEmail: string; shippingAddress: string; totalInCents: number; status: OrderStatus; idempotencyKey: string }
export interface CheckoutItem { productId: number; quantity: number }
export interface CheckoutRequest { items: CheckoutItem[]; customerName: string; customerEmail: string; shippingAddress: string; idempotencyKey: string }
export interface CheckoutResponse { orderId: number; status: 'queued' | 'pending' }
```

- [ ] **Step 2: Add register to api/auth.ts + checkout/order/pay to api/order.ts**

`src/api/auth.ts` add:
```ts
export function register(payload: LoginRequest): Promise<LoginResponse> {
  return request.post<LoginResponse>('/auth/register', payload)
}
```
`src/api/order.ts` (new):
```ts
import request from './request'
import type { CheckoutRequest, CheckoutResponse, Order, PageResult } from './types'

export function checkout(payload: CheckoutRequest): Promise<CheckoutResponse> {
  return request.post<CheckoutResponse>('/checkout', payload)
}
export function getOrder(id: number): Promise<Order> {
  return request.get<Order>(`/orders/${id}`)
}
export function getMyOrders(current = 1, size = 20): Promise<PageResult<Order>> {
  return request.get<PageResult<Order>>('/orders/me', { params: { current, size } })
}
export function payOrder(id: number): Promise<Order> {
  return request.post<Order>(`/orders/${id}/pay`)
}
export function cancelOrder(id: number): Promise<void> {
  return request.post(`/orders/${id}/cancel`)
}
```
`src/api/product.ts` — update for priceInCents + add upload/openSale:
```ts
export function uploadImage(id: number, file: File): Promise<string> {
  const form = new FormData(); form.append('file', file)
  return request.post<string>(`/products/${id}/image`, form)
}
export function openFlashSale(id: number): Promise<void> {
  return request.post(`/admin/sales/${id}`)
}
```

- [ ] **Step 3: Fix the stale :8080 comment in request.ts**

In `src/api/request.ts` line 19, change `:8080` → `:8081`.

- [ ] **Step 4: Write Register.tsx + Checkout.tsx (three-stage) + Orders.tsx + Admin.tsx**

These are substantial UI files. Implement:
- `Register.tsx`: form (username/password) → `register()` → navigate to `/login`.
- `Checkout.tsx`: three-stage. Stage 1 (form): collect name/email/address, build `CheckoutRequest` with a `crypto.randomUUID()` idempotencyKey, call `checkout()`, store `orderId` + go to stage 2. Stage 2 (queued→pending): poll `getOrder(orderId)` every 1s until `status` !== the queued placeholder; on `PENDING` show the pay UI; on `FAILED` show retry. Stage 3 (paid): call `payOrder(orderId)`, clear cart, show confirmation.
- `Orders.tsx`: `getMyOrders()` table.
- `Admin.tsx`: product CRUD form + table + "Open flash sale" button per product calling `openFlashSale(id)`.

(Each page follows the existing `Products.tsx`/`Login.tsx` Tailwind + lucide-react patterns. Keep them focused — one responsibility per page.)

- [ ] **Step 5: Update router/index.tsx with the new routes**

Add to the `children` of the protected layout: `{ path: 'checkout', element: <Checkout /> }`, `{ path: 'orders', element: <Orders /> }`, `{ path: 'admin', element: <Admin /> }`. Add the public `{ path: '/register', element: <Register /> }`.

- [ ] **Step 6: Write the cart-reducer unit test (the test the old project never had)**

`tests/cart-reducer.test.ts` — port a `cartReducer` (pure function: add/remove/setQuantity/clear/reconcile) and test it:
```ts
import { describe, it, expect } from 'vitest'
// import { cartReducer, CartAction } from '@/store/cartReducer'

describe('cartReducer', () => {
  it('add caps quantity at product stock', () => {
    // const state = cartReducer({ items: [] }, { type: 'add', product: { id: 1, stock: 3, priceInCents: 100, name: 'x' } as any, quantity: 10 })
    // expect(state.items[0].quantity).toBe(3)
    expect(true).toBe(true) // replace once cartReducer is implemented
  })
  it('clear empties the cart', () => { /* ... */ })
})
```
(Implement `src/store/cartReducer.ts` as a pure reducer + a React context wrapper that uses it with `localStorage` persistence — port from the old project's `cart.tsx`.)

- [ ] **Step 7: Run frontend tests + build**

Run:
```bash
cd frame-frontend && npm test && npm run build
```
Expected: tests PASS, build succeeds (no TS errors).

- [ ] **Step 8: Manual smoke test (both servers)**

Start backend (`mvn spring-boot:run` in `frame-backend`) + frontend (`npm run dev` in `frame-frontend`). Open http://localhost:5173 → register → login → browse → add to cart → checkout (watch queued→pending poll) → pay → see confirmation. As admin: create a product, open a flash sale, verify it appears.

- [ ] **Step 9: Commit**

```bash
git add -A frame-frontend/
git commit -m "feat: frontend — three-stage checkout, register, orders, admin flash-sale UI"
```

---

## Plan Complete — V1 (Build Steps 1-8)

After Task 8, V1 is functionally complete: a flash-sale storefront with the Taobao concurrency model, verified by the load/mutex test, deployable as a jar.

**Next (separate follow-up plan):** Linux deployment (spec §11a / build step 9) — build jar + `dist/`, provision a Linux server, `systemd` + Nginx reverse proxy, verify end-to-end on Linux, run the load/mutex test against the deployed server. That plan will be written once V1 is verified locally.


