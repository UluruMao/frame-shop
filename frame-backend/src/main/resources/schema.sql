CREATE DATABASE IF NOT EXISTS frame_db
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE frame_db;

DROP TABLE IF EXISTS `chat_message`;
DROP TABLE IF EXISTS `chat_session`;
DROP TABLE IF EXISTS `operation_log`;
DROP TABLE IF EXISTS `order_item`;
DROP TABLE IF EXISTS `review`;
DROP TABLE IF EXISTS `coin_ledger`;
DROP TABLE IF EXISTS `orders`;
DROP TABLE IF EXISTS `product`;
DROP TABLE IF EXISTS `user`;

CREATE TABLE `user` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `username`        VARCHAR(64)  NOT NULL,
    `password_hash`   VARCHAR(100) NOT NULL,
    `role`            VARCHAR(16)  NOT NULL DEFAULT 'USER',
    `phone`           VARCHAR(20)  DEFAULT NULL,
    `nickname`        VARCHAR(64)  DEFAULT NULL,
    `avatar_url`      VARCHAR(512) DEFAULT NULL,
    `signature`       VARCHAR(256) DEFAULT NULL,
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    `failed_attempts` INT          NOT NULL DEFAULT 0,
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_username` (`username`),
    UNIQUE KEY `uk_user_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `operation_log` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT       DEFAULT NULL,
    `username`      VARCHAR(64) DEFAULT NULL,
    `method`        VARCHAR(10)  NOT NULL,
    `uri`           VARCHAR(256) NOT NULL,
    `params`        TEXT,
    `result_status` VARCHAR(16) NOT NULL,
    `cost_ms`       INT          NOT NULL DEFAULT 0,
    `ip`            VARCHAR(64) DEFAULT NULL,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_oplog_user` (`user_id`),
    KEY `idx_oplog_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `product` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT,
    `name`          VARCHAR(128) NOT NULL,
    `price_in_cents` INT          NOT NULL,
    `stock`         INT          NOT NULL DEFAULT 0,
    `image_url`     VARCHAR(2048) DEFAULT NULL,
    `category`      VARCHAR(64)  DEFAULT NULL,
    `is_flash_sale` TINYINT(1)   NOT NULL DEFAULT 0,
    `flash_sale_at` DATETIME     DEFAULT NULL,
    `discount_percent` INT       DEFAULT NULL,
    `description`   VARCHAR(512) DEFAULT NULL,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_product_category` (`category`),
    KEY `idx_product_flash` (`is_flash_sale`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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

CREATE TABLE `orders` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`        BIGINT       NOT NULL,
    `customer_name`  VARCHAR(128) NOT NULL,
    `customer_email` VARCHAR(128) NOT NULL,
    `shipping_address` VARCHAR(256) NOT NULL,
    `total_in_cents` INT          NOT NULL,
    `paid_in_cents`  INT          NOT NULL DEFAULT 0,
    `status`         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    `idempotency_key` VARCHAR(64) NOT NULL,
    `customer_phone`   VARCHAR(20)  DEFAULT NULL,
    `payment_method`   VARCHAR(16)  DEFAULT NULL,
    `tracking_number`  VARCHAR(64)  DEFAULT NULL,
    `shipped_at`       DATETIME     DEFAULT NULL,
    `delivered_at`     DATETIME     DEFAULT NULL,
    `completed_at`     DATETIME     DEFAULT NULL,
    `paid_at`          DATETIME     DEFAULT NULL,
    `refund_status`    VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    `refund_reason`    VARCHAR(256) DEFAULT NULL,
    `cancel_reason`    VARCHAR(256) DEFAULT NULL,
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

CREATE TABLE `coin_ledger` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT       NOT NULL,
    `amount`        INT          NOT NULL,
    `balance_after` INT          NOT NULL,
    `source`        VARCHAR(32)  NOT NULL,
    `ref_order_id`  BIGINT       DEFAULT NULL,
    `ref_credit_id` BIGINT       DEFAULT NULL,
    `ref_credit_expires_at` DATETIME DEFAULT NULL, -- snapshot of the consumed credit's expires_at (for refundCoins to restore)
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

CREATE TABLE `chat_session` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT       NOT NULL,
    `admin_id`      BIGINT,
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- At most one OPEN session per user: open_user_key = user_id when OPEN else NULL.
    -- NULLs don't collide in a unique index, so CLOSED rows are unconstrained.
    `open_user_key` BIGINT AS (IF(status='OPEN', user_id, NULL)) VIRTUAL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_open_user` (`open_user_key`),
    INDEX `idx_session_user` (`user_id`),
    INDEX `idx_session_admin` (`admin_id`),
    INDEX `idx_session_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `chat_message` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `session_id`  BIGINT       NOT NULL,
    `sender_type` VARCHAR(16)  NOT NULL,
    `sender_id`   BIGINT       NOT NULL,
    `content`     TEXT         NOT NULL,
    `image_url`   VARCHAR(2048),
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_msg_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `user` (`username`, `password_hash`, `role`, `phone`) VALUES
    ('admin', '$2a$10$7FzLk9ItV8zh8fPkg9WJKuTOy.cxJ61NXxGVmYaSAGg9RlBxYYfKC', 'ADMIN', '13800000000');

-- Original demo products (ids 1..2).
INSERT INTO `product` (`name`, `price_in_cents`, `stock`, `category`, `is_flash_sale`, `description`) VALUES
    ('Flash Widget', 1999, 5, 'widgets', 1, 'Limited flash-sale item'),
    ('Demo Gadget', 2950, 50, 'gadgets', 0, 'Regular item');

-- ===== DEMO SEED (not production data) =====
-- Demo users all share the admin password hash (admin123) for local testing:
--   username: demo1 / demo2 / demo3 / demo4   password: admin123
-- Re-run this whole file (mariadb -u root -p < src/main/resources/schema.sql) to reset to this seed.
-- The seed is sized so the admin Dashboard shows believable numbers (~¥6.1k revenue,
-- ~54 orders across 6 months + statuses, ~4.2 avg rating). FK references below assume
-- auto-increment starts at 1 after the DROP TABLE block above:
--   user:    admin=1, demo1=2, demo2=3, demo3=4, demo4=5
--   product: 1..7 (the 2 original rows above + the 5 inserted below)
--   orders:  1..54 (table is empty before this INSERT)
INSERT INTO `user` (`username`, `password_hash`, `role`, `phone`, `nickname`) VALUES
    ('demo1', '$2a$10$7FzLk9ItV8zh8fPkg9WJKuTOy.cxJ61NXxGVmYaSAGg9RlBxYYfKC', 'USER', '13900000001', '买家小明'),
    ('demo2', '$2a$10$7FzLk9ItV8zh8fPkg9WJKuTOy.cxJ61NXxGVmYaSAGg9RlBxYYfKC', 'USER', '13900000002', '买家小红'),
    ('demo3', '$2a$10$7FzLk9ItV8zh8fPkg9WJKuTOy.cxJ61NXxGVmYaSAGg9RlBxYYfKC', 'USER', '13900000003', '买家阿强'),
    ('demo4', '$2a$10$7FzLk9ItV8zh8fPkg9WJKuTOy.cxJ61NXxGVmYaSAGg9RlBxYYfKC', 'USER', '13900000004', '买家大伟');

-- More products across categories (ids 3..7; the 2 original rows above keep ids 1..2).
INSERT INTO `product` (`name`, `price_in_cents`, `stock`, `category`, `is_flash_sale`, `description`) VALUES
    ('无线蓝牙耳机', 12900, 80, '数码', 0, '降噪蓝牙耳机'),
    ('机械键盘', 25900, 40, '数码', 0, 'RGB机械键盘'),
    ('保温杯', 4900, 120, '家居', 0, '316不锈钢保温杯'),
    ('运动手环', 9900, 60, '数码', 1, '心率运动手环'),
    ('棉柔毛巾', 1990, 200, '家居', 0, '纯棉毛巾两条装');

-- Demo orders spread across statuses + the last 6 months of paid_at (2026-03..2026-08).
-- Mix of PAID/SHIPPED/DELIVERED/COMPLETED/REFUNDED/CANCELLED. paid_in_cents = paid amount
-- (after coin discount) for PAID/SHIPPED/DELIVERED/COMPLETED; 0 for CANCELLED; original paid
-- amount kept for REFUNDED (revenue query excludes REFUNDED/CANCELLED anyway). idempotency_key
-- is unique per row. ~9 orders/month × 6 months = 54 rows (clears the >50 test threshold).
INSERT INTO `orders` (`user_id`, `customer_name`, `customer_email`, `shipping_address`, `total_in_cents`, `paid_in_cents`, `status`, `idempotency_key`, `payment_method`, `paid_at`, `refund_status`, `create_time`) VALUES
    -- 2026-03 (9 orders)
    (2, '小明', 'demo1@example.com', '北京市朝阳区A街1号', 12900, 12900, 'COMPLETED', 'idem-seed-001', 'WECHAT', '2026-03-05 10:00:00', 'NONE', '2026-03-05 09:55:00'),
    (3, '小红', 'demo2@example.com', '上海市浦东新区B路2号', 25900, 25900, 'COMPLETED', 'idem-seed-002', 'ALIPAY', '2026-03-08 14:00:00', 'NONE', '2026-03-08 13:50:00'),
    (4, '阿强', 'demo3@example.com', '广州市天河区C巷3号', 4900, 4900, 'DELIVERED', 'idem-seed-003', 'BANK_CARD', '2026-03-12 09:00:00', 'NONE', '2026-03-12 08:50:00'),
    (5, '大伟', 'demo4@example.com', '深圳市南山区D栋4号', 9900, 9900, 'COMPLETED', 'idem-seed-004', 'WECHAT', '2026-03-15 16:00:00', 'NONE', '2026-03-15 15:50:00'),
    (2, '小明', 'demo1@example.com', '北京市朝阳区A街1号', 12900, 12900, 'COMPLETED', 'idem-seed-005', 'ALIPAY', '2026-03-18 11:00:00', 'NONE', '2026-03-18 10:50:00'),
    (3, '小红', 'demo2@example.com', '上海市浦东新区B路2号', 1990, 1990, 'DELIVERED', 'idem-seed-006', 'WECHAT', '2026-03-22 13:00:00', 'NONE', '2026-03-22 12:50:00'),
    (4, '阿强', 'demo3@example.com', '广州市天河区C巷3号', 25900, 25900, 'COMPLETED', 'idem-seed-007', 'ALIPAY', '2026-03-25 15:00:00', 'NONE', '2026-03-25 14:50:00'),
    (5, '大伟', 'demo4@example.com', '深圳市南山区D栋4号', 4900, 4900, 'COMPLETED', 'idem-seed-008', 'BANK_CARD', '2026-03-28 10:00:00', 'NONE', '2026-03-28 09:50:00'),
    (2, '小明', 'demo1@example.com', '北京市朝阳区A街1号', 9900, 9900, 'REFUNDED', 'idem-seed-009', 'WECHAT', '2026-03-30 10:00:00', 'APPROVED', '2026-03-30 09:50:00'),
    -- 2026-04 (9 orders)
    (3, '小红', 'demo2@example.com', '上海市浦东新区B路2号', 12900, 12900, 'COMPLETED', 'idem-seed-010', 'WECHAT', '2026-04-02 09:00:00', 'NONE', '2026-04-02 08:50:00'),
    (4, '阿强', 'demo3@example.com', '广州市天河区C巷3号', 25900, 25900, 'COMPLETED', 'idem-seed-011', 'ALIPAY', '2026-04-05 16:00:00', 'NONE', '2026-04-05 15:50:00'),
    (5, '大伟', 'demo4@example.com', '深圳市南山区D栋4号', 4900, 4900, 'DELIVERED', 'idem-seed-012', 'BANK_CARD', '2026-04-08 11:00:00', 'NONE', '2026-04-08 10:50:00'),
    (2, '小明', 'demo1@example.com', '北京市朝阳区A街1号', 9900, 9900, 'COMPLETED', 'idem-seed-013', 'WECHAT', '2026-04-11 13:00:00', 'NONE', '2026-04-11 12:50:00'),
    (3, '小红', 'demo2@example.com', '上海市浦东新区B路2号', 12900, 12900, 'COMPLETED', 'idem-seed-014', 'ALIPAY', '2026-04-14 15:00:00', 'NONE', '2026-04-14 14:50:00'),
    (4, '阿强', 'demo3@example.com', '广州市天河区C巷3号', 1990, 1990, 'DELIVERED', 'idem-seed-015', 'WECHAT', '2026-04-17 10:00:00', 'NONE', '2026-04-17 09:50:00'),
    (5, '大伟', 'demo4@example.com', '深圳市南山区D栋4号', 25900, 25900, 'COMPLETED', 'idem-seed-016', 'BANK_CARD', '2026-04-20 13:00:00', 'NONE', '2026-04-20 12:50:00'),
    (2, '小明', 'demo1@example.com', '北京市朝阳区A街1号', 4900, 4900, 'COMPLETED', 'idem-seed-017', 'ALIPAY', '2026-04-23 15:00:00', 'NONE', '2026-04-23 14:50:00'),
    (3, '小红', 'demo2@example.com', '上海市浦东新区B路2号', 9900, 9900, 'CANCELLED', 'idem-seed-018', 'WECHAT', NULL, 'NONE', '2026-04-26 10:00:00'),
    -- 2026-05 (9 orders)
    (4, '阿强', 'demo3@example.com', '广州市天河区C巷3号', 12900, 12900, 'COMPLETED', 'idem-seed-019', 'WECHAT', '2026-05-02 09:00:00', 'NONE', '2026-05-02 08:50:00'),
    (5, '大伟', 'demo4@example.com', '深圳市南山区D栋4号', 25900, 25900, 'COMPLETED', 'idem-seed-020', 'ALIPAY', '2026-05-05 16:00:00', 'NONE', '2026-05-05 15:50:00'),
    (2, '小明', 'demo1@example.com', '北京市朝阳区A街1号', 4900, 4900, 'DELIVERED', 'idem-seed-021', 'BANK_CARD', '2026-05-08 11:00:00', 'NONE', '2026-05-08 10:50:00'),
    (3, '小红', 'demo2@example.com', '上海市浦东新区B路2号', 9900, 9900, 'COMPLETED', 'idem-seed-022', 'WECHAT', '2026-05-11 13:00:00', 'NONE', '2026-05-11 12:50:00'),
    (4, '阿强', 'demo3@example.com', '广州市天河区C巷3号', 12900, 12900, 'COMPLETED', 'idem-seed-023', 'ALIPAY', '2026-05-14 15:00:00', 'NONE', '2026-05-14 14:50:00'),
    (5, '大伟', 'demo4@example.com', '深圳市南山区D栋4号', 1990, 1990, 'DELIVERED', 'idem-seed-024', 'WECHAT', '2026-05-17 10:00:00', 'NONE', '2026-05-17 09:50:00'),
    (2, '小明', 'demo1@example.com', '北京市朝阳区A街1号', 25900, 25900, 'COMPLETED', 'idem-seed-025', 'BANK_CARD', '2026-05-20 13:00:00', 'NONE', '2026-05-20 12:50:00'),
    (3, '小红', 'demo2@example.com', '上海市浦东新区B路2号', 4900, 4900, 'COMPLETED', 'idem-seed-026', 'ALIPAY', '2026-05-23 15:00:00', 'NONE', '2026-05-23 14:50:00'),
    (4, '阿强', 'demo3@example.com', '广州市天河区C巷3号', 9900, 9900, 'REFUNDED', 'idem-seed-027', 'WECHAT', '2026-05-26 10:00:00', 'APPROVED', '2026-05-26 09:50:00'),
    -- 2026-06 (9 orders)
    (5, '大伟', 'demo4@example.com', '深圳市南山区D栋4号', 12900, 12900, 'COMPLETED', 'idem-seed-028', 'WECHAT', '2026-06-02 09:00:00', 'NONE', '2026-06-02 08:50:00'),
    (2, '小明', 'demo1@example.com', '北京市朝阳区A街1号', 25900, 25900, 'COMPLETED', 'idem-seed-029', 'ALIPAY', '2026-06-05 16:00:00', 'NONE', '2026-06-05 15:50:00'),
    (3, '小红', 'demo2@example.com', '上海市浦东新区B路2号', 4900, 4900, 'DELIVERED', 'idem-seed-030', 'BANK_CARD', '2026-06-08 11:00:00', 'NONE', '2026-06-08 10:50:00'),
    (4, '阿强', 'demo3@example.com', '广州市天河区C巷3号', 9900, 9900, 'COMPLETED', 'idem-seed-031', 'WECHAT', '2026-06-11 13:00:00', 'NONE', '2026-06-11 12:50:00'),
    (5, '大伟', 'demo4@example.com', '深圳市南山区D栋4号', 12900, 12900, 'COMPLETED', 'idem-seed-032', 'ALIPAY', '2026-06-14 15:00:00', 'NONE', '2026-06-14 14:50:00'),
    (2, '小明', 'demo1@example.com', '北京市朝阳区A街1号', 1990, 1990, 'DELIVERED', 'idem-seed-033', 'WECHAT', '2026-06-17 10:00:00', 'NONE', '2026-06-17 09:50:00'),
    (3, '小红', 'demo2@example.com', '上海市浦东新区B路2号', 25900, 25900, 'COMPLETED', 'idem-seed-034', 'BANK_CARD', '2026-06-20 13:00:00', 'NONE', '2026-06-20 12:50:00'),
    (4, '阿强', 'demo3@example.com', '广州市天河区C巷3号', 4900, 4900, 'COMPLETED', 'idem-seed-035', 'ALIPAY', '2026-06-23 15:00:00', 'NONE', '2026-06-23 14:50:00'),
    (5, '大伟', 'demo4@example.com', '深圳市南山区D栋4号', 9900, 9900, 'CANCELLED', 'idem-seed-036', 'WECHAT', NULL, 'NONE', '2026-06-26 10:00:00'),
    -- 2026-07 (9 orders)
    (2, '小明', 'demo1@example.com', '北京市朝阳区A街1号', 12900, 12900, 'COMPLETED', 'idem-seed-037', 'WECHAT', '2026-07-02 09:00:00', 'NONE', '2026-07-02 08:50:00'),
    (3, '小红', 'demo2@example.com', '上海市浦东新区B路2号', 25900, 25900, 'COMPLETED', 'idem-seed-038', 'ALIPAY', '2026-07-05 16:00:00', 'NONE', '2026-07-05 15:50:00'),
    (4, '阿强', 'demo3@example.com', '广州市天河区C巷3号', 4900, 4900, 'DELIVERED', 'idem-seed-039', 'BANK_CARD', '2026-07-08 11:00:00', 'NONE', '2026-07-08 10:50:00'),
    (5, '大伟', 'demo4@example.com', '深圳市南山区D栋4号', 9900, 9900, 'COMPLETED', 'idem-seed-040', 'WECHAT', '2026-07-11 13:00:00', 'NONE', '2026-07-11 12:50:00'),
    (2, '小明', 'demo1@example.com', '北京市朝阳区A街1号', 12900, 12900, 'SHIPPED', 'idem-seed-041', 'ALIPAY', '2026-07-14 15:00:00', 'NONE', '2026-07-14 14:50:00'),
    (3, '小红', 'demo2@example.com', '上海市浦东新区B路2号', 1990, 1990, 'DELIVERED', 'idem-seed-042', 'WECHAT', '2026-07-17 10:00:00', 'NONE', '2026-07-17 09:50:00'),
    (4, '阿强', 'demo3@example.com', '广州市天河区C巷3号', 25900, 25900, 'COMPLETED', 'idem-seed-043', 'BANK_CARD', '2026-07-20 13:00:00', 'NONE', '2026-07-20 12:50:00'),
    (5, '大伟', 'demo4@example.com', '深圳市南山区D栋4号', 4900, 4900, 'COMPLETED', 'idem-seed-044', 'ALIPAY', '2026-07-23 15:00:00', 'NONE', '2026-07-23 14:50:00'),
    (2, '小明', 'demo1@example.com', '北京市朝阳区A街1号', 9900, 9900, 'PAID', 'idem-seed-045', 'WECHAT', '2026-07-26 10:00:00', 'NONE', '2026-07-26 09:50:00'),
    -- 2026-08 (9 orders; today is 2026-08-26)
    (3, '小红', 'demo2@example.com', '上海市浦东新区B路2号', 12900, 12900, 'COMPLETED', 'idem-seed-046', 'WECHAT', '2026-08-02 09:00:00', 'NONE', '2026-08-02 08:50:00'),
    (4, '阿强', 'demo3@example.com', '广州市天河区C巷3号', 25900, 25900, 'COMPLETED', 'idem-seed-047', 'ALIPAY', '2026-08-05 16:00:00', 'NONE', '2026-08-05 15:50:00'),
    (5, '大伟', 'demo4@example.com', '深圳市南山区D栋4号', 4900, 4900, 'DELIVERED', 'idem-seed-048', 'BANK_CARD', '2026-08-08 11:00:00', 'NONE', '2026-08-08 10:50:00'),
    (2, '小明', 'demo1@example.com', '北京市朝阳区A街1号', 9900, 9900, 'COMPLETED', 'idem-seed-049', 'WECHAT', '2026-08-11 13:00:00', 'NONE', '2026-08-11 12:50:00'),
    (3, '小红', 'demo2@example.com', '上海市浦东新区B路2号', 12900, 12900, 'SHIPPED', 'idem-seed-050', 'ALIPAY', '2026-08-14 15:00:00', 'NONE', '2026-08-14 14:50:00'),
    (4, '阿强', 'demo3@example.com', '广州市天河区C巷3号', 1990, 1990, 'DELIVERED', 'idem-seed-051', 'WECHAT', '2026-08-17 10:00:00', 'NONE', '2026-08-17 09:50:00'),
    (5, '大伟', 'demo4@example.com', '深圳市南山区D栋4号', 25900, 25900, 'COMPLETED', 'idem-seed-052', 'BANK_CARD', '2026-08-20 13:00:00', 'NONE', '2026-08-20 12:50:00'),
    (2, '小明', 'demo1@example.com', '北京市朝阳区A街1号', 4900, 4900, 'PAID', 'idem-seed-053', 'ALIPAY', '2026-08-23 15:00:00', 'NONE', '2026-08-23 14:50:00'),
    (3, '小红', 'demo2@example.com', '上海市浦东新区B路2号', 9900, 9900, 'COMPLETED', 'idem-seed-054', 'WECHAT', '2026-08-25 10:00:00', 'NONE', '2026-08-25 09:50:00');

-- Order items matching the orders above (product snapshots). product_id 1..7 reference the
-- product rows; order_id 1..54 reference the orders inserted above (auto-increment from 1).
INSERT INTO `order_item` (`order_id`, `product_id`, `name`, `price_in_cents`, `quantity`) VALUES
    (1, 3, '无线蓝牙耳机', 12900, 1),
    (2, 4, '机械键盘', 25900, 1),
    (3, 5, '保温杯', 4900, 1),
    (4, 6, '运动手环', 9900, 1),
    (5, 3, '无线蓝牙耳机', 12900, 1),
    (6, 7, '棉柔毛巾', 1990, 1),
    (7, 4, '机械键盘', 25900, 1),
    (8, 5, '保温杯', 4900, 1),
    (9, 6, '运动手环', 9900, 1),
    (10, 3, '无线蓝牙耳机', 12900, 1),
    (11, 4, '机械键盘', 25900, 1),
    (12, 5, '保温杯', 4900, 1),
    (13, 6, '运动手环', 9900, 1),
    (14, 3, '无线蓝牙耳机', 12900, 1),
    (15, 7, '棉柔毛巾', 1990, 1),
    (16, 4, '机械键盘', 25900, 1),
    (17, 5, '保温杯', 4900, 1),
    (18, 6, '运动手环', 9900, 1),
    (19, 3, '无线蓝牙耳机', 12900, 1),
    (20, 4, '机械键盘', 25900, 1),
    (21, 5, '保温杯', 4900, 1),
    (22, 6, '运动手环', 9900, 1),
    (23, 3, '无线蓝牙耳机', 12900, 1),
    (24, 7, '棉柔毛巾', 1990, 1),
    (25, 4, '机械键盘', 25900, 1),
    (26, 5, '保温杯', 4900, 1),
    (27, 6, '运动手环', 9900, 1),
    (28, 3, '无线蓝牙耳机', 12900, 1),
    (29, 4, '机械键盘', 25900, 1),
    (30, 5, '保温杯', 4900, 1),
    (31, 6, '运动手环', 9900, 1),
    (32, 3, '无线蓝牙耳机', 12900, 1),
    (33, 7, '棉柔毛巾', 1990, 1),
    (34, 4, '机械键盘', 25900, 1),
    (35, 5, '保温杯', 4900, 1),
    (36, 6, '运动手环', 9900, 1),
    (37, 3, '无线蓝牙耳机', 12900, 1),
    (38, 4, '机械键盘', 25900, 1),
    (39, 5, '保温杯', 4900, 1),
    (40, 6, '运动手环', 9900, 1),
    (41, 3, '无线蓝牙耳机', 12900, 1),
    (42, 7, '棉柔毛巾', 1990, 1),
    (43, 4, '机械键盘', 25900, 1),
    (44, 5, '保温杯', 4900, 1),
    (45, 6, '运动手环', 9900, 1),
    (46, 3, '无线蓝牙耳机', 12900, 1),
    (47, 4, '机械键盘', 25900, 1),
    (48, 5, '保温杯', 4900, 1),
    (49, 6, '运动手环', 9900, 1),
    (50, 3, '无线蓝牙耳机', 12900, 1),
    (51, 7, '棉柔毛巾', 1990, 1),
    (52, 4, '机械键盘', 25900, 1),
    (53, 5, '保温杯', 4900, 1),
    (54, 6, '运动手环', 9900, 1);

-- Reviews with varied 3-5★ ratings so avgRating lands ~4.2. The review table has a unique
-- key on (order_id, product_id), so each reviewed order appears once. Reviews are placed on
-- mostly COMPLETED/DELIVERED orders (a few SHIPPED slip through; not on CANCELLED/REFUNDED/PAID).
-- A few 3★ reviews pull the average below 5; most are 4-5★.
INSERT INTO `review` (`order_id`, `user_id`, `product_id`, `quality_stars`, `logistics_stars`, `service_stars`, `content`) VALUES
    (1, 2, 3, 5, 5, 4, '耳机音质不错，降噪效果好'),
    (2, 3, 4, 4, 5, 5, '键盘手感很好，RGB灯漂亮'),
    (3, 4, 5, 5, 4, 4, '保温效果出色，一天还热'),
    (4, 5, 6, 4, 4, 5, '手环功能齐全，性价比高'),
    (5, 2, 3, 5, 5, 5, '第二次购买了，依旧满意'),
    (6, 3, 7, 4, 3, 4, '毛巾一般般，物流有点慢'),
    (7, 4, 4, 5, 4, 4, '机械键盘做工扎实'),
    (8, 5, 5, 4, 5, 5, '保温杯颜值高，容量合适'),
    (10, 3, 3, 5, 5, 4, '耳机佩戴舒适，续航给力'),
    (11, 4, 4, 4, 4, 5, '键盘不错，按键声音好听'),
    (12, 5, 5, 5, 4, 4, '保温杯质量好，已推荐朋友'),
    (13, 2, 6, 4, 5, 4, '手环监测准确，app好用'),
    (14, 3, 3, 5, 5, 5, '耳机降噪惊艳，超预期'),
    (15, 4, 7, 3, 3, 3, '毛巾偏薄，价格略贵'),
    (16, 5, 4, 4, 4, 4, '键盘整体不错，有轻微漏光'),
    (17, 2, 5, 5, 5, 5, '保温杯很满意，回购'),
    (19, 4, 3, 4, 5, 4, '耳机不错，包装精美'),
    (20, 5, 4, 5, 4, 5, '键盘用了一周，体验很好'),
    (21, 2, 5, 4, 4, 4, '保温杯可以，就是颜色单一'),
    (22, 3, 6, 5, 5, 5, '手环数据准确，续航久'),
    (23, 4, 3, 4, 4, 5, '耳机降噪一般，价格合适'),
    (24, 5, 7, 5, 5, 4, '毛巾柔软，吸水性好'),
    (25, 2, 4, 3, 4, 3, '键盘有键位失灵，换货后好了'),
    (26, 3, 5, 5, 5, 5, '保温杯完美，无可挑剔'),
    (28, 5, 3, 4, 5, 4, '耳机佩戴舒适，建议入'),
    (29, 2, 4, 5, 4, 5, '键盘手感一流，推荐'),
    (30, 3, 5, 4, 4, 4, '保温杯用着不错'),
    (31, 4, 6, 5, 5, 5, '手环很轻，戴着睡觉无感'),
    (32, 5, 3, 4, 5, 4, '耳机音质可以，降噪够用'),
    (34, 4, 4, 5, 4, 5, '键盘性价比高，入手不亏'),
    (37, 2, 3, 5, 5, 5, '耳机第三次回购，依旧棒'),
    (38, 3, 4, 4, 4, 4, '键盘不错，灯效可调'),
    (39, 4, 5, 5, 5, 4, '保温杯质量一如既往的好'),
    (40, 5, 6, 4, 5, 5, '手环升级款，功能更多了'),
    (43, 4, 4, 5, 4, 4, '键盘用着顺手，好评'),
    (44, 5, 5, 4, 4, 5, '保温杯颜色好看，保温久'),
    (46, 3, 3, 5, 5, 5, '耳机降噪强，通话清晰'),
    (47, 4, 4, 4, 5, 4, '键盘轴体手感好，推荐'),
    (48, 5, 5, 5, 4, 5, '保温杯回购，依旧满意'),
    (49, 2, 6, 4, 4, 4, '手环数据还行，app偶尔卡'),
    (50, 3, 3, 5, 5, 5, '耳机颜值高，音质在线'),
    (52, 5, 4, 4, 5, 4, '键盘整体不错，值得入手'),
    (54, 3, 6, 5, 5, 5, '手环续航给力，监测准');

-- A few coin-ledger rows for the demo users (DAILY_LOGIN reward; uk_coin_daily allows one
-- per user per login_date). balance_after reflects the running balance per user.
INSERT INTO `coin_ledger` (`user_id`, `amount`, `balance_after`, `source`, `login_date`) VALUES
    (2, 100, 100, 'DAILY_LOGIN', '2026-08-25'),
    (3, 100, 100, 'DAILY_LOGIN', '2026-08-25'),
    (4, 100, 100, 'DAILY_LOGIN', '2026-08-25'),
    (5, 100, 100, 'DAILY_LOGIN', '2026-08-25'),
    (2, 50, 150, 'DAILY_LOGIN', '2026-08-26'),
    (3, 50, 150, 'DAILY_LOGIN', '2026-08-26');
