-- schema-prod.sql — non-destructive version of schema.sql for production deployment.
-- Safe to run on an existing database (uses CREATE TABLE IF NOT EXISTS, no DROP).
-- Run: mariadb -u root -p < schema-prod.sql

CREATE DATABASE IF NOT EXISTS frame_db
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE frame_db;

CREATE TABLE IF NOT EXISTS `user` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `username`      VARCHAR(64)  NOT NULL,
    `password_hash` VARCHAR(100) NOT NULL,
    `role`          VARCHAR(16)  NOT NULL DEFAULT 'USER',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `product` (
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

CREATE TABLE IF NOT EXISTS `orders` (
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

CREATE TABLE IF NOT EXISTS `chat_session` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT       NOT NULL,
    `admin_id`    BIGINT,
    `status`      VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
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
    `image_url`   VARCHAR(512),
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_msg_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Create a dedicated app user (not root) for production
CREATE USER IF NOT EXISTS 'frame_app'@'localhost' IDENTIFIED BY 'change_me_in_prod';
GRANT ALL PRIVILEGES ON frame_db.* TO 'frame_app'@'localhost';
FLUSH PRIVILEGES;

-- Seed admin user (only if not already present)
INSERT IGNORE INTO `user` (`username`, `password_hash`, `role`) VALUES
    ('admin', '$2a$10$7FzLk9ItV8zh8fPkg9WJKuTOy.cxJ61NXxGVmYaSAGg9RlBxYYfKC', 'ADMIN');

-- Seed demo products (only if product table is empty)
-- Flash Widget: is_flash_sale=1 with flash_sale_at=NOW() so the time-gated scheduler
-- (StockGateServiceImpl.openDueFlashSales, runs every 10s) auto-opens it on boot — no
-- admin click needed to demo the flow. (The manual POST /admin/sales/{id} endpoint still
-- works for immediate/open-now testing.) Demo Gadget is a regular, non-flash product.
INSERT IGNORE INTO `product` (`name`, `price_in_cents`, `stock`, `category`, `is_flash_sale`, `flash_sale_at`, `description`) VALUES
    ('Flash Widget', 1999, 5, 'widgets', 1, NOW(), 'Limited flash-sale item'),
    ('Demo Gadget', 2950, 50, 'gadgets', 0, NULL, 'Regular item');
