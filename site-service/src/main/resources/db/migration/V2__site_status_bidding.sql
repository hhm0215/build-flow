-- 현장 상태에 BIDDING(수주 전·견적 중) 추가 (2026-10-23).
-- Hibernate ddl-auto=update가 기존 DB에 만드는 정의(값 알파벳 순)와 같게 둔다.
-- 이미 BIDDING이 있는 DB(ddl-auto 기동 후 baseline)에서 다시 실행돼도 같은 정의라 무해하다.
ALTER TABLE `sites`
  MODIFY COLUMN `status` enum('BIDDING','COMPLETED','IN_PROGRESS','SETTLING','WARRANTY') COLLATE utf8mb4_unicode_ci NOT NULL;
