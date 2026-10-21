-- Captured from local MySQL 8 SHOW CREATE TABLE on 2026-10-20.
-- Fresh isolated databases only. Existing databases require verified backup,
-- production DDL comparison, and an explicit one-time baseline before activation.
CREATE TABLE `tax_invoices` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `counterparty` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `issue_date` date DEFAULT NULL,
  `memo` text COLLATE utf8mb4_unicode_ci,
  `payment_confirmed` bit(1) NOT NULL,
  `payment_date` date DEFAULT NULL,
  `site_id` bigint NOT NULL,
  `supply_amount` decimal(15,2) NOT NULL,
  `tax_amount` decimal(15,2) NOT NULL,
  `total_amount` decimal(15,2) NOT NULL,
  `type` enum('PURCHASE','SALES') COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `outbox_events` (
  `event_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `attempts` int NOT NULL,
  `claim_token` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `last_error` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `lease_until` datetime(6) DEFAULT NULL,
  `next_attempt_at` datetime(6) NOT NULL,
  `payload_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `record_key` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sent_at` datetime(6) DEFAULT NULL,
  `status` enum('CLAIMED','PENDING','SENT') COLLATE utf8mb4_unicode_ci NOT NULL,
  `topic` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`event_id`),
  KEY `idx_outbox_pending` (`status`,`next_attempt_at`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
