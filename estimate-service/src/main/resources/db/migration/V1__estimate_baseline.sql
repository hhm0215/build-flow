-- Captured from local MySQL 8 SHOW CREATE TABLE on 2026-10-20.
-- Fresh isolated databases only. Existing databases require verified backup,
-- production DDL comparison, and an explicit one-time baseline before activation.
CREATE TABLE `estimates` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `estimate_date` date NOT NULL,
  `memo` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `site_id` bigint NOT NULL,
  `status` enum('CONFIRMED','DRAFT') COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `total_amount` decimal(15,2) NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `estimate_items` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `amount` decimal(15,2) NOT NULL,
  `item_name` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `quantity` decimal(10,2) NOT NULL,
  `unit` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `unit_price` decimal(15,2) NOT NULL,
  `estimate_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKqgbltt3sxh9ec1eyv22mmr6w4` (`estimate_id`),
  CONSTRAINT `FKqgbltt3sxh9ec1eyv22mmr6w4` FOREIGN KEY (`estimate_id`) REFERENCES `estimates` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `outbox_events` (
  `event_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `attempts` int NOT NULL,
  `claim_token` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `last_error` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `lease_until` datetime(6) DEFAULT NULL,
  `next_attempt_at` datetime(6) NOT NULL,
  `payload_json` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `record_key` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sent_at` datetime(6) DEFAULT NULL,
  `status` enum('CLAIMED','PENDING','SENT') COLLATE utf8mb4_unicode_ci NOT NULL,
  `topic` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`event_id`),
  KEY `idx_estimate_outbox_due` (`status`,`next_attempt_at`,`created_at`),
  KEY `idx_estimate_outbox_lease` (`status`,`lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
