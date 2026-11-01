package com.buildflow.site.domain.dashboard.service;

import com.buildflow.site.domain.dashboard.dto.DashboardStatsResponse;
import com.buildflow.site.domain.dashboard.dto.DashboardStatsResponse.SiteProfitSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 모델 출력 사후 검증: 사실 목록 밖의 외국 문자·현장명·금액·할 일 모순을 잡고, 사실 목록에 있던 표기는 통과시킨다. */
class SummaryOutputGuardTest {

    private static SiteProfitSummary site(String name, String status, String estimate, String purchase, String rate) {
        BigDecimal e = new BigDecimal(estimate);
        BigDecimal p = new BigDecimal(purchase);
        return SiteProfitSummary.builder()
                .siteName(name).status(status)
                .estimateAmount(e).purchaseAmount(p)
                .margin(e.subtract(p)).marginRate(new BigDecimal(rate))
                .build();
    }

    /** 정상 1 + 적자 1 + 견적 미등록 1. 합계 11,000,000 / 8,200,000 / 2,800,000원, 25.45%. */
    private static DashboardSummaryFacts mixedFacts() {
        return DashboardSummaryFacts.from(DashboardStatsResponse.builder()
                .totalSites(3)
                .sitesByStatus(Map.of("IN_PROGRESS", 2, "SETTLING", 1))
                .totalEstimateAmount(new BigDecimal("11000000"))
                .totalPurchaseAmount(new BigDecimal("8200000"))
                .totalMargin(new BigDecimal("2800000"))
                .averageMarginRate(new BigDecimal("25.45"))
                .siteProfits(List.of(
                        site("Tower A동", "IN_PROGRESS", "10000000", "7000000", "30.00"),
                        site("적자현장", "SETTLING", "1000000", "1200000", "-20.00"),
                        site("[QA] 빈 현장", "IN_PROGRESS", "0", "0", "0")))
                .build());
    }

    private static final String GOOD = """
            ### 1. 전체 현황
            전체 현장은 3개입니다(시공 중 2개, 정산 중 1개).

            ### 2. 손익
            합계 견적 11,000,000원, 매입 8,200,000원, 마진 2,800,000원, 마진율 25.45%입니다.
            Tower A동 (시공 중)은 마진율 30.00%로 정상입니다.

            ### 3. 주의 현장
            - 적자현장 (정산 중): 적자 (마진 200,000원 손실, 마진율 -20.00%)

            ### 4. 다음 할 일
            - 적자현장: 매입 내역과 추가공사 견적 누락 여부 확인
            - [QA] 빈 현장: 견적 등록
            AI 요약은 기준 10% 미만을 저마진으로 봅니다.
            """;

    @Test
    void 사실_목록만_옮긴_정상_출력은_통과하고_영문_현장명과_AI는_허용한다() {
        assertThat(SummaryOutputGuard.violations(GOOD, mixedFacts())).isEmpty();
    }

    @Test
    void 한글_낱말에_라틴_문자가_섞이면_걸린다() {
        String output = GOOD.replace("마진율 25.45%", "마argin율 25.45%");

        assertThat(SummaryOutputGuard.violations(output, mixedFacts()))
                .containsExactly(SummaryOutputGuard.FOREIGN_SCRIPT);
    }

    @Test
    void 한자나_베트남어_글자가_섞여도_걸린다() {
        assertThat(SummaryOutputGuard.violations(GOOD.replace("정상입니다", "正常입니다"), mixedFacts()))
                .containsExactly(SummaryOutputGuard.FOREIGN_SCRIPT);
        assertThat(SummaryOutputGuard.violations(GOOD.replace("견적 등록", "ước정 등록"), mixedFacts()))
                .containsExactly(SummaryOutputGuard.FOREIGN_SCRIPT);
    }

    @Test
    void 사실_목록에_없는_현장명이_나오면_걸린다() {
        DashboardSummaryFacts facts = mixedFacts();

        assertThat(SummaryOutputGuard.violations(GOOD + "\n- 가짜현장: 견적 등록", facts))
                .containsExactly(SummaryOutputGuard.UNKNOWN_SITE);
        assertThat(SummaryOutputGuard.violations(GOOD.replace("Tower A동 (시공 중)", "한강빌딩 (시공 중)"), facts))
                .containsExactly(SummaryOutputGuard.UNKNOWN_SITE);
    }

    @Test
    void 일반_낱말에_붙은_현장은_현장명으로_보지_않는다() {
        String output = GOOD + "\n전체현장 중 적자 현장은 1곳이고 나머지현장은 정상입니다.";

        assertThat(SummaryOutputGuard.violations(output, mixedFacts())).isEmpty();
    }

    @Test
    void 사실_목록에_없는_금액이나_비율이_나오면_걸린다() {
        DashboardSummaryFacts facts = mixedFacts();

        assertThat(SummaryOutputGuard.violations(GOOD.replace("2,800,000원", "2,900,000원"), facts))
                .containsExactly(SummaryOutputGuard.NEW_NUMBER);
        assertThat(SummaryOutputGuard.violations(GOOD.replace("25.45%", "26%"), facts))
                .containsExactly(SummaryOutputGuard.NEW_NUMBER);
        // 원 단위 값을 만 단위로 부풀린 표기(8,000.00 → 8,000만 원)는 값이 같아 보여도 새 숫자다.
        assertThat(SummaryOutputGuard.violations(GOOD.replace("매입 8,200,000원", "매입 820만 원"), facts))
                .containsExactly(SummaryOutputGuard.NEW_NUMBER);
    }

    @Test
    void 같은_값의_소수_자리_차이와_부호_생략은_새_숫자가_아니다() {
        String output = GOOD.replace("마진율 30.00%", "마진율 30%").replace("마진율 -20.00%", "마진율 20%");

        assertThat(SummaryOutputGuard.violations(output, mixedFacts())).isEmpty();
    }

    @Test
    void 할_일이_있는데_조치할_일이_없다고_덧붙이면_걸린다() {
        String output = GOOD + "\n지금 따로 조치할 일은 없습니다.";

        assertThat(SummaryOutputGuard.violations(output, mixedFacts()))
                .containsExactly(SummaryOutputGuard.CONTRADICTION);
    }

    @Test
    void 빈_출력은_걸린다() {
        assertThat(SummaryOutputGuard.violations(null, mixedFacts())).containsExactly(SummaryOutputGuard.EMPTY);
        assertThat(SummaryOutputGuard.violations("  \n", mixedFacts())).containsExactly(SummaryOutputGuard.EMPTY);
    }

    @Test
    void 서버_결정_문구는_스스로_검증을_통과한다() {
        DashboardSummaryFacts facts = mixedFacts();

        assertThat(SummaryOutputGuard.violations(facts.deterministicSummary(), facts)).isEmpty();
    }
}
