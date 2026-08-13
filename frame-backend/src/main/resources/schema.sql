-- ============================================================================
-- frame-backend demo schema — MariaDB
-- Run this once against your MariaDB instance to create the demo database + table.
--
-- Usage:
--   mariadb -u root -p < schema.sql
-- ============================================================================

CREATE DATABASE IF NOT EXISTS frame_db
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE frame_db;

-- Demo product table. `stock` is present so DB-level conditional updates
-- (where stock >= qty) can be demonstrated as a concurrency-safe mutex later.
DROP TABLE IF EXISTS `demo_product`;
CREATE TABLE `demo_product` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'primary key',
    `name`        VARCHAR(128) NOT NULL COMMENT 'product name',
    `price`       DECIMAL(10, 2) NOT NULL COMMENT 'unit price',
    `stock`       INT          NOT NULL DEFAULT 0 COMMENT 'stock on hand',
    `description` VARCHAR(512) DEFAULT NULL COMMENT 'free-text description',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'creation time',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'last update',
    PRIMARY KEY (`id`),
    KEY `idx_name` (`name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'demo product';

-- Seed a couple of rows so the list endpoint returns something immediately.
INSERT INTO `demo_product` (`name`, `price`, `stock`, `description`)
VALUES
    ('Demo Widget', 19.99, 100, 'A sample widget for the template demo'),
    ('Demo Gadget', 29.50, 50,  'A sample gadget for the template demo');
