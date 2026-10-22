package com.buildflow.tax.domain.taxinvoice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 분할 입금 도입 전 "입금 확인"으로 완료된 매출 계산서에 총액 입금 1건을 이관해
 * 목록·취소 화면이 같은 원장을 보게 한다. 실패해도 조회는 총액 입금으로 간주하므로 기동을 막지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyPaymentLedgerBackfill implements ApplicationRunner {

    private final TaxPaymentService taxPaymentService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int migrated = taxPaymentService.backfillLegacyConfirmed();
            if (migrated > 0) {
                log.info("기존 입금 확인 세금계산서 {}건을 입금 내역으로 이관했습니다.", migrated);
            }
        } catch (RuntimeException e) {
            log.warn("기존 입금 확인 이관 실패 — 조회는 총액 입금으로 계속 계산합니다: {}", e.getMessage());
        }
    }
}
