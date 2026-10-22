-- 2026-10-23 매입 항목 ↔ 증빙 매입 세금계산서(tax-service) 연결. nullable, 기존 행은 미연결(NULL).
-- 운영(ddl-auto update)은 Hibernate가 같은 컬럼·인덱스를 먼저 만들 수 있으므로, 이 opt-in purchase-flyway
-- 프로파일용 파일은 기존 DB를 baseline(1) 후 migrate해도 실패하지 않도록 존재 여부를 확인한 뒤 추가한다.
SET @evidence_column_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchases'
    AND COLUMN_NAME = 'evidence_tax_invoice_id');
SET @evidence_column_sql := IF(@evidence_column_exists = 0,
  'ALTER TABLE `purchases` ADD COLUMN `evidence_tax_invoice_id` bigint DEFAULT NULL',
  'SELECT 1');
PREPARE evidence_column_stmt FROM @evidence_column_sql;
EXECUTE evidence_column_stmt;
DEALLOCATE PREPARE evidence_column_stmt;

SET @evidence_index_exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchases'
    AND INDEX_NAME = 'idx_purchases_evidence_tax_invoice');
SET @evidence_index_sql := IF(@evidence_index_exists = 0,
  'CREATE INDEX `idx_purchases_evidence_tax_invoice` ON `purchases` (`evidence_tax_invoice_id`)',
  'SELECT 1');
PREPARE evidence_index_stmt FROM @evidence_index_sql;
EXECUTE evidence_index_stmt;
DEALLOCATE PREPARE evidence_index_stmt;
