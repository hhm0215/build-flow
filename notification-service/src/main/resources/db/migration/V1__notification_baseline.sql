-- Captured from local MySQL 8 SHOW CREATE TABLE on 2026-10-22 (develop 8bb640b entities).
-- Fresh isolated databases only. Existing databases require verified backup,
-- production DDL comparison, and an explicit one-time baseline before activation.
CREATE TABLE `notifications` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `event_type` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `is_read` bit(1) NOT NULL,
  `message` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `site_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `processed_notification_events` (
  `event_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `processed_at` datetime(6) NOT NULL,
  PRIMARY KEY (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `outbox_events` (
  `event_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `attempts` int NOT NULL,
  `claim_token` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `last_error` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `lease_until` datetime(6) DEFAULT NULL,
  `next_attempt_at` datetime(6) NOT NULL,
  `payload_json` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `record_key` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sent_at` datetime(6) DEFAULT NULL,
  `status` enum('CLAIMED','PENDING','SENT','SUPPRESSED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `topic` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `suppressed_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`event_id`),
  KEY `idx_outbox_due` (`status`,`next_attempt_at`),
  KEY `idx_outbox_record` (`topic`,`record_key`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `defect_warranties` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `coverage_amount` bigint DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `end_date` date DEFAULT NULL,
  `file_path` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `insurance_company` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `last_expiring_alert_sent_at` date DEFAULT NULL,
  `memo` text COLLATE utf8mb4_unicode_ci,
  `ocr_status` enum('FAILED','MANUAL','PENDING','SUCCESS') COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'MANUAL',
  `policy_number` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `site_id` bigint NOT NULL,
  `start_date` date DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `expiry_confirmed_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `warranty_audit_events` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `action` enum('CREATED','DELETED','MANUAL_UPDATED','OCR_APPLIED','OCR_FAILED','UPLOADED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `actor` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `coverage_amount` bigint DEFAULT NULL,
  `end_date` date DEFAULT NULL,
  `expiry_confirmed_at` datetime(6) DEFAULT NULL,
  `insurance_company` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `occurred_at` datetime(6) NOT NULL,
  `ocr_status` enum('FAILED','MANUAL','PENDING','SUCCESS') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `policy_number` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `start_date` date DEFAULT NULL,
  `warranty_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_warranty_audit_warranty` (`warranty_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `warranty_file_retentions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `deleted_at` datetime(6) NOT NULL,
  `purge_after` date NOT NULL,
  `purged_at` datetime(6) DEFAULT NULL,
  `stored_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `warranty_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_warranty_retention_due` (`purged_at`,`purge_after`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
