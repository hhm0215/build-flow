-- Captured from the verified 2026-09-29 MySQL no-data dump, buildflow_auth.admin_accounts.
-- Do not add administrator credentials or IF NOT EXISTS to a versioned migration.
CREATE TABLE `admin_accounts` (
  `id` bigint NOT NULL,
  `login_id` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `password` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `login_id` (`login_id`),
  CONSTRAINT `chk_single_admin_id` CHECK ((`id` = 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
