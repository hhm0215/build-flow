-- 2026-10-23 매출 세금계산서 분할 입금·입금 취소 원장.
-- 운영(ddl-auto update)은 Hibernate가 같은 테이블을 먼저 만들 수 있고, 기존 "입금 확인" 자료는
-- LegacyPaymentLedgerBackfill이 기동 시 총액 1건으로 이관한다. 이 파일은 opt-in tax-flyway 프로파일용이며
-- 기존 DB를 baseline(1) 후 migrate해도 실패·중복 이관이 없도록 멱등으로 작성한다.
CREATE TABLE IF NOT EXISTS `tax_payments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `amount` decimal(15,2) NOT NULL,
  `cancel_reason` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `canceled_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `memo` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `payment_date` date NOT NULL,
  `tax_invoice_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tax_payments_invoice` (`tax_invoice_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `tax_payments` (`amount`, `created_at`, `memo`, `payment_date`, `tax_invoice_id`)
SELECT t.`total_amount`, NOW(6), '기존 입금 확인 이관',
       COALESCE(t.`payment_date`, DATE(t.`created_at`)), t.`id`
FROM `tax_invoices` t
WHERE t.`payment_confirmed` = b'1' AND t.`total_amount` > 0
  AND NOT EXISTS (SELECT 1 FROM `tax_payments` p WHERE p.`tax_invoice_id` = t.`id`);
