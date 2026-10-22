-- 2026-10-23 견적 수정본·추가공사 관계와 대체됨(SUPERSEDED) 상태.
-- 새 컬럼은 모두 nullable이며 기존 행은 kind NULL = ORIGINAL, chain_root_id NULL = 자기 자신이 루트로 취급한다.
-- 운영(ddl-auto update)은 Hibernate가 같은 컬럼·인덱스·enum 정의를 먼저 만들 수 있으므로, opt-in
-- estimate-flyway 프로파일에서 기존 DB를 baseline(1) 후 migrate해도 실패하지 않도록 존재 여부를 확인한다.
-- enum 값 순서는 Hibernate ddl-auto=update가 만드는 정의(알파벳 순)와 같게 둔다.
ALTER TABLE `estimates`
  MODIFY COLUMN `status` enum('CONFIRMED','DRAFT','SUPERSEDED') COLLATE utf8mb4_unicode_ci NOT NULL;

SET @kind_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'estimates' AND COLUMN_NAME = 'kind');
SET @kind_sql := IF(@kind_exists = 0,
  'ALTER TABLE `estimates` ADD COLUMN `kind` enum(''ADDITIONAL'',''ORIGINAL'',''REVISION'') COLLATE utf8mb4_unicode_ci DEFAULT NULL',
  'SELECT 1');
PREPARE kind_stmt FROM @kind_sql;
EXECUTE kind_stmt;
DEALLOCATE PREPARE kind_stmt;

SET @revision_of_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'estimates' AND COLUMN_NAME = 'revision_of_id');
SET @revision_of_sql := IF(@revision_of_exists = 0,
  'ALTER TABLE `estimates` ADD COLUMN `revision_of_id` bigint DEFAULT NULL',
  'SELECT 1');
PREPARE revision_of_stmt FROM @revision_of_sql;
EXECUTE revision_of_stmt;
DEALLOCATE PREPARE revision_of_stmt;

SET @chain_root_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'estimates' AND COLUMN_NAME = 'chain_root_id');
SET @chain_root_sql := IF(@chain_root_exists = 0,
  'ALTER TABLE `estimates` ADD COLUMN `chain_root_id` bigint DEFAULT NULL',
  'SELECT 1');
PREPARE chain_root_stmt FROM @chain_root_sql;
EXECUTE chain_root_stmt;
DEALLOCATE PREPARE chain_root_stmt;

SET @superseded_by_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'estimates' AND COLUMN_NAME = 'superseded_by_id');
SET @superseded_by_sql := IF(@superseded_by_exists = 0,
  'ALTER TABLE `estimates` ADD COLUMN `superseded_by_id` bigint DEFAULT NULL',
  'SELECT 1');
PREPARE superseded_by_stmt FROM @superseded_by_sql;
EXECUTE superseded_by_stmt;
DEALLOCATE PREPARE superseded_by_stmt;

SET @chain_root_index_exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'estimates'
    AND INDEX_NAME = 'idx_estimates_chain_root');
SET @chain_root_index_sql := IF(@chain_root_index_exists = 0,
  'CREATE INDEX `idx_estimates_chain_root` ON `estimates` (`chain_root_id`)',
  'SELECT 1');
PREPARE chain_root_index_stmt FROM @chain_root_index_sql;
EXECUTE chain_root_index_stmt;
DEALLOCATE PREPARE chain_root_index_stmt;
