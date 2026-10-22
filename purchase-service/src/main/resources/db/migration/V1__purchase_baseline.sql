-- Captured from local MySQL 8 SHOW CREATE TABLE on 2026-10-20.
-- Fresh isolated databases only. Existing databases require verified backup,
-- production DDL comparison, and an explicit one-time baseline before activation.
CREATE TABLE `purchases` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `event_revision` bigint NOT NULL,
  `item_name` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `memo` text COLLATE utf8mb4_unicode_ci,
  `purchase_date` date DEFAULT NULL,
  `quantity` int NOT NULL,
  `site_id` bigint NOT NULL,
  `supplier` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `total_amount` decimal(15,2) NOT NULL,
  `unit_price` decimal(12,2) NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `outbox_events` (
  `event_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `attempts` int NOT NULL,
  `claim_token` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `last_error` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `lease_until` datetime(6) DEFAULT NULL,
  `next_attempt_at` datetime(6) NOT NULL,
  `payload_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `record_key` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sent_at` datetime(6) DEFAULT NULL,
  `status` enum('CLAIMED','PENDING','SENT') COLLATE utf8mb4_unicode_ci NOT NULL,
  `topic` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`event_id`),
  KEY `idx_outbox_pending` (`status`,`next_attempt_at`,`created_at`),
  KEY `idx_outbox_lease` (`status`,`lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
