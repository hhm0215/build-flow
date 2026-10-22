-- Captured from local MySQL 8 SHOW CREATE TABLE on 2026-10-22 (develop f31d1eb entities).
-- Fresh isolated databases only. Existing databases require verified backup,
-- production DDL comparison, and an explicit one-time baseline before activation.
CREATE TABLE `clients` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `address` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `business_no` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `company_name` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `email` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `memo` text COLLATE utf8mb4_unicode_ci,
  `phone` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `representative` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `archived_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `sites` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `address` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `end_date` date DEFAULT NULL,
  `memo` text COLLATE utf8mb4_unicode_ci,
  `site_name` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `start_date` date DEFAULT NULL,
  `status` enum('COMPLETED','IN_PROGRESS','SETTLING','WARRANTY') COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `client_id` bigint DEFAULT NULL,
  `archived_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKgfwfh8o6o73fp6vj1lr82hvp1` (`client_id`),
  CONSTRAINT `FKgfwfh8o6o73fp6vj1lr82hvp1` FOREIGN KEY (`client_id`) REFERENCES `clients` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `site_profits` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `margin` decimal(15,2) NOT NULL,
  `margin_rate` decimal(5,2) NOT NULL,
  `site_id` bigint NOT NULL,
  `total_estimate_amount` decimal(15,2) NOT NULL,
  `total_purchase_amount` decimal(15,2) NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKcta3320q5fosffw8q5c9t317l` (`site_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `processed_profit_events` (
  `event_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `event_type` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `site_id` bigint NOT NULL,
  PRIMARY KEY (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `purchase_profit_projections` (
  `purchase_id` bigint NOT NULL,
  `current_amount` decimal(15,2) NOT NULL,
  `deleted` bit(1) NOT NULL,
  `last_event_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `last_revision` bigint NOT NULL,
  `site_id` bigint NOT NULL,
  PRIMARY KEY (`purchase_id`),
  KEY `idx_purchase_profit_projection_site` (`site_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
