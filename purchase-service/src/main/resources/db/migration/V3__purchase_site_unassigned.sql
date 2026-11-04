-- 2026-11-01 현장 미배정 매입 허용(사용자 결정: 홈택스 매입 세금계산서를 '미배정'으로 일괄 적재 후 화면에서 현장 배정).
-- site_id NOT NULL → NULL. 기존 행 값은 바뀌지 않는다(제약 완화만, 데이터 변환 없음).
-- 운영(ddl-auto update)은 Hibernate가 기존 컬럼 nullability를 바꾸지 않으므로 기본 프로파일은
-- PurchaseSiteIdNullableSchemaPatch가 같은 변경을 한다. 이 opt-in purchase-flyway 프로파일용 파일은 기존 DB를
-- baseline(1) 후 migrate하거나 이미 패치된 DB에서도 실패하지 않도록 현재 nullability를 확인한 뒤 변경한다.
SET @site_id_nullable := (
  SELECT IS_NULLABLE FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchases'
    AND COLUMN_NAME = 'site_id');
SET @site_id_sql := IF(@site_id_nullable = 'NO',
  'ALTER TABLE `purchases` MODIFY COLUMN `site_id` bigint DEFAULT NULL',
  'SELECT 1');
PREPARE site_id_stmt FROM @site_id_sql;
EXECUTE site_id_stmt;
DEALLOCATE PREPARE site_id_stmt;
