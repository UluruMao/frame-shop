CREATE DATABASE IF NOT EXISTS frame_db
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE frame_db;

DROP TABLE IF EXISTS `operation_log`;
DROP TABLE IF EXISTS `order_item`;
DROP TABLE IF EXISTS `review`;
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

INSERT INTO `user` (`username`, `password_hash`, `role`, `phone`) VALUES
    ('admin', '$2a$10$7FzLk9ItV8zh8fPkg9WJKuTOy.cxJ61NXxGVmYaSAGg9RlBxYYfKC', 'ADMIN', '13800000000');

INSERT INTO `product` (`name`, `price_in_cents`, `stock`, `category`, `is_flash_sale`, `description`) VALUES
    ('Flash Widget', 1999, 5, 'widgets', 1, 'Limited flash-sale item'),
    ('Demo Gadget', 2950, 50, 'gadgets', 0, 'Regular item');
