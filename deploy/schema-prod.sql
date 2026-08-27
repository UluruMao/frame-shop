-- schema-prod.sql — non-destructive production schema, kept in sync with schema.sql.
-- Safe to run on an existing database: CREATE TABLE IF NOT EXISTS (no DROP),
-- additive ALTER TABLE ... ADD COLUMN ... IF NOT EXISTS, INSERT IGNORE for seeds.
-- Run: mariadb -u root -p < schema-prod.sql
--
-- NOTE: MariaDB supports ADD COLUMN IF NOT EXISTS (since 10.5) and
-- ADD INDEX IF NOT EXISTS, so re-running is idempotent.

CREATE DATABASE IF NOT EXISTS frame_db
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE frame_db;

-- ============================================================================
-- Tables (idempotent: CREATE TABLE IF NOT EXISTS)
-- ============================================================================

CREATE TABLE IF NOT EXISTS `user` (
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

CREATE TABLE IF NOT EXISTS `operation_log` (
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

CREATE TABLE IF NOT EXISTS `product` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT,
    `name`          VARCHAR(128) NOT NULL,
    `price_in_cents` INT          NOT NULL,
    `stock`         INT           NOT NULL DEFAULT 0,
    `image_url`     VARCHAR(2048)  DEFAULT NULL,
    `category`      VARCHAR(64)   DEFAULT NULL,
    `is_flash_sale` TINYINT(1)    NOT NULL DEFAULT 0,
    `flash_sale_at` DATETIME      DEFAULT NULL,
    `description`   VARCHAR(512)  DEFAULT NULL,
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_product_category` (`category`),
    KEY `idx_product_flash` (`is_flash_sale`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `review` (
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

CREATE TABLE IF NOT EXISTS `orders` (
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

CREATE TABLE IF NOT EXISTS `order_item` (
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

CREATE TABLE IF NOT EXISTS `coin_ledger` (
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

CREATE TABLE IF NOT EXISTS `chat_session` (
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

CREATE TABLE IF NOT EXISTS `chat_message` (
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

-- ============================================================================
-- Additive column migrations (idempotent: IF NOT EXISTS)
-- For VMs that ran an older schema-prod.sql (Phase B/C) and are missing
-- Phase D/E columns on existing tables. Safe no-ops on a fresh DB.
-- ============================================================================

-- user: Phase E added profile + lockout columns
ALTER TABLE `user` ADD COLUMN IF NOT EXISTS `phone`           VARCHAR(20)  DEFAULT NULL AFTER `role`;
ALTER TABLE `user` ADD COLUMN IF NOT EXISTS `nickname`        VARCHAR(64)  DEFAULT NULL AFTER `phone`;
ALTER TABLE `user` ADD COLUMN IF NOT EXISTS `avatar_url`      VARCHAR(512) DEFAULT NULL AFTER `nickname`;
ALTER TABLE `user` ADD COLUMN IF NOT EXISTS `signature`       VARCHAR(256) DEFAULT NULL AFTER `avatar_url`;
ALTER TABLE `user` ADD COLUMN IF NOT EXISTS `status`          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' AFTER `signature`;
ALTER TABLE `user` ADD COLUMN IF NOT EXISTS `failed_attempts` INT          NOT NULL DEFAULT 0 AFTER `status`;
ALTER TABLE `user` ADD INDEX IF NOT EXISTS `uk_user_phone` (`phone`);

-- orders: Phase C added payment/lifecycle/refund columns
ALTER TABLE `orders` ADD COLUMN IF NOT EXISTS `paid_in_cents`   INT          NOT NULL DEFAULT 0 AFTER `total_in_cents`;
ALTER TABLE `orders` ADD COLUMN IF NOT EXISTS `customer_phone`  VARCHAR(20)  DEFAULT NULL AFTER `idempotency_key`;
ALTER TABLE `orders` ADD COLUMN IF NOT EXISTS `payment_method`  VARCHAR(16)  DEFAULT NULL AFTER `customer_phone`;
ALTER TABLE `orders` ADD COLUMN IF NOT EXISTS `tracking_number` VARCHAR(64)  DEFAULT NULL AFTER `payment_method`;
ALTER TABLE `orders` ADD COLUMN IF NOT EXISTS `shipped_at`      DATETIME     DEFAULT NULL AFTER `tracking_number`;
ALTER TABLE `orders` ADD COLUMN IF NOT EXISTS `delivered_at`    DATETIME     DEFAULT NULL AFTER `shipped_at`;
ALTER TABLE `orders` ADD COLUMN IF NOT EXISTS `completed_at`    DATETIME     DEFAULT NULL AFTER `delivered_at`;
ALTER TABLE `orders` ADD COLUMN IF NOT EXISTS `paid_at`         DATETIME     DEFAULT NULL AFTER `completed_at`;
ALTER TABLE `orders` ADD COLUMN IF NOT EXISTS `refund_status`   VARCHAR(16)  NOT NULL DEFAULT 'NONE' AFTER `paid_at`;
ALTER TABLE `orders` ADD COLUMN IF NOT EXISTS `refund_reason`   VARCHAR(256) DEFAULT NULL AFTER `refund_status`;
ALTER TABLE `orders` ADD COLUMN IF NOT EXISTS `cancel_reason`   VARCHAR(256) DEFAULT NULL AFTER `refund_reason`;

-- product: Phase F list-cache + description (description may already exist)
ALTER TABLE `product` ADD COLUMN IF NOT EXISTS `description` VARCHAR(512) DEFAULT NULL AFTER `flash_sale_at`;
-- product: flash-sale discount percentage (1-99; null = no discount even if flash)
ALTER TABLE `product` ADD COLUMN IF NOT EXISTS `discount_percent` INT DEFAULT NULL AFTER `flash_sale_at`;
-- product: widen image_url from VARCHAR(512) → VARCHAR(2048) — real CDN/search URLs exceed 512.
ALTER TABLE `product` MODIFY COLUMN `image_url` VARCHAR(2048) DEFAULT NULL;

-- ============================================================================
-- Dedicated app user (not root) for production
-- ============================================================================
CREATE USER IF NOT EXISTS 'frame_app'@'localhost' IDENTIFIED BY 'change_me_in_prod';
GRANT ALL PRIVILEGES ON frame_db.* TO 'frame_app'@'localhost';
FLUSH PRIVILEGES;
-- NOTE: CREATE USER IF NOT EXISTS does NOT change an existing user's password.
-- To rotate later: ALTER USER 'frame_app'@'localhost' IDENTIFIED BY 'new_pw';

-- ============================================================================
-- Seeds (idempotent: INSERT IGNORE)
-- ============================================================================

-- Seed admin user (only if not already present). phone=13800000000 for UC tests.
INSERT IGNORE INTO `user` (`username`, `password_hash`, `role`, `phone`) VALUES
    ('admin', '$2a$10$7FzLk9ItV8zh8fPkg9WJKuTOy.cxJ61NXxGVmYaSAGg9RlBxYYfKC', 'ADMIN', '13800000000');

-- Seed demo products (only if product table is empty).
-- Flash Widget: is_flash_sale=1 with flash_sale_at=NOW() so the time-gated scheduler
-- (StockGateServiceImpl.openDueFlashSales, runs every 10s) auto-opens it on boot — no
-- admin click needed to demo the flow. Demo Gadget is a regular, non-flash product.
INSERT IGNORE INTO `product` (`name`, `price_in_cents`, `stock`, `category`, `is_flash_sale`, `flash_sale_at`, `description`) VALUES
    ('Flash Widget', 1999, 5, 'widgets', 1, NOW(), 'Limited flash-sale item'),
    ('Demo Gadget', 2950, 50, 'gadgets', 0, NULL, 'Regular item');
