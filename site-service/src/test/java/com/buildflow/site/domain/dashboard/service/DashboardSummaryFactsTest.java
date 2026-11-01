package com.buildflow.site.domain.dashboard.service;

import com.buildflow.site.domain.dashboard.dto.DashboardStatsResponse;
import com.buildflow.site.domain.dashboard.dto.DashboardStatsResponse.SiteProfitSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 대시보드 요약 사실 분류: 견적 미등록·수주 전은 평가에서 빼고, 적자·저마진은 서버가 결정적으로 판정한다. */
class DashboardSummaryFactsTest {

    private static SiteProfitSummary site(String name, String status, String estimate, String purchase, String rate) {
        BigDecimal e = new BigDecimal(estimate);
        BigDecimal p = new BigDecimal(purchase);
        return SiteProfitSummary.builder()
                .siteName(name).status(status)
                .estimateAmount(e).purchaseAmount(p)
                .margin(e.subtract(p)).marginRate(new BigDecimal(rate))
                .build();
    }

    private static DashboardStatsResponse stats(Map<String, Integer> byStatus, String totalEstimate, String totalPurchase,
                                                String averageRate, SiteProfitSummary... sites) {
        BigDecimal e = new BigDecimal(totalEstimate);
        BigDecimal p = new BigDecimal(totalPurchase);
        return DashboardStatsResponse.builder()
                .totalSites(sites.length)
                .sitesByStatus(byStatus)
                .totalEstimateAmount(e).totalPurchaseAmount(p).totalMargin(e.subtract(p))
                .averageMarginRate(new BigDecimal(averageRate))
                .siteProfits(List.of(sites))
                .build();
    }

    @Test
    void 현장이_없으면_데이터_없음이고_현장_없음_안내를_준다() {
        DashboardSummaryFacts facts = DashboardSummaryFacts.from(stats(Map.of(), "0", "0", "0"));

        assertThat(facts.hasData()).isFalse();
        assertThat(facts.noDataMessage()).isEqualTo(DashboardSummaryFacts.NO_SITE_MESSAGE);
    }

    @Test
    void 견적_매입이_모두_0인_현장만_있으면_데이터_없음이고_손실로_판정하지_않는다() {
        DashboardSummaryFacts facts = DashboardSummaryFacts.from(stats(Map.of("IN_PROGRESS", 1), "0", "0", "0",
                site("[QA] AI 요약 현장", "IN_PROGRESS", "0", "0", "0")));

        assertThat(facts.hasData()).isFalse();
        assertThat(facts.warningSites()).isEmpty();
        assertThat(facts.evaluatedSites()).isEmpty();
        assertThat(facts.noEstimateSites()).containsExactly("[QA] AI 요약 현장 (시공 중): 견적·매입 미등록");
        assertThat(facts.averageMarginRate()).isEqualTo("평가 대상 없음");
        assertThat(facts.noDataMessage())
                .startsWith("아직 견적·매입이 등록된 현장이 없습니다.")
                .contains("현장 1개")
                .doesNotContain("적자");
    }

    @Test
    void 혼합_데이터에서_적자_저마진만_주의로_견적_미등록과_수주_전은_평가에서_뺀다() {
        DashboardSummaryFacts facts = DashboardSummaryFacts.from(stats(
                Map.of("IN_PROGRESS", 4, "SETTLING", 1, "BIDDING", 1),
                "17000000", "11050000", "35.00",
                site("정상현장", "IN_PROGRESS", "10000000", "7000000", "30.00"),
                site("적자현장", "SETTLING", "1000000", "1200000", "-20.00"),
                site("저마진현장", "IN_PROGRESS", "2000000", "1850000", "7.50"),
                site("빈현장", "IN_PROGRESS", "0", "0", "0"),
                site("매입만현장", "IN_PROGRESS", "0", "1000000", "0"),
                site("견적만현장", "IN_PROGRESS", "4000000", "0", "100.00"),
                site("입찰현장", "BIDDING", "5000000", "0", "100.00")));

        assertThat(facts.hasData()).isTrue();
        assertThat(facts.evaluatedSites()).containsExactly(
                "정상현장 (시공 중): 견적 10,000,000원, 매입 7,000,000원, 마진 3,000,000원, 마진율 30.00%",
                "적자현장 (정산 중): 견적 1,000,000원, 매입 1,200,000원, 마진 -200,000원, 마진율 -20.00%",
                "저마진현장 (시공 중): 견적 2,000,000원, 매입 1,850,000원, 마진 150,000원, 마진율 7.50%");
        assertThat(facts.warningSites()).containsExactly(
                "적자현장 (정산 중): 적자 (마진 -200,000원, 마진율 -20.00%)",
                "저마진현장 (시공 중): 저마진 (마진율 7.50%, 기준 10% 미만)");
        assertThat(facts.noEstimateSites()).containsExactly(
                "빈현장 (시공 중): 견적·매입 미등록",
                "매입만현장 (시공 중): 견적 미등록, 매입 1,000,000원 등록됨");
        assertThat(facts.noPurchaseSites()).containsExactly("견적만현장 (시공 중): 견적 4,000,000원, 매입 미입력");
        assertThat(facts.biddingSites()).containsExactly("입찰현장 (수주 전): 계약 전 견적 5,000,000원");
        // 마진율은 stats 평균(35.00%)이 아니라 매입 입력 현장 3개만으로: 2,950,000 / 13,000,000 = 22.69%
        assertThat(facts.averageMarginRate()).isEqualTo("22.69%");
        assertThat(facts.nextActions()).containsExactly(
                "적자현장: 매입 내역과 추가공사 견적 누락 여부 확인",
                "저마진현장: 남은 매입 계획과 마진 확인",
                "매입 입력 필요(1개)",
                "빈현장: 견적 등록",
                "매입만현장: 견적 등록");

        String prompt = facts.toPrompt();
        assertThat(prompt)
                .contains("- 손익 평가 대상 현장: 3개 (매입 미입력 1개, 견적 미등록 2개, 수주 전 1개는 평가 제외)")
                .contains("- 합계(수주 전 제외, 견적 미등록 현장의 매입 포함): 견적 17,000,000원, 매입 11,050,000원\n")
                .contains("- 마진(매입 입력 현장 3개 기준): 견적 13,000,000원, 매입 10,050,000원, 마진 2,950,000원, 마진율 22.69%")
                .contains("- 매입 미입력 1개 현장은 마진율 계산에서 제외")
                .contains("## 주의 현장 (서버 판정: 적자 또는 마진율 10% 미만)\n\n- 적자현장")
                .contains("## 매입 미입력 현장 (견적만 등록, 마진율 계산 제외)\n\n- 견적만현장")
                .contains("## 견적 미등록 현장 (손실 아님, 데이터 없음)\n\n- 빈현장")
                .doesNotContain("빈현장 (시공 중): 견적 0")
                .doesNotContain("100.00%")
                .doesNotContain("35.00%");
    }

    @Test
    void 서버_결정_문구는_현황_손익_주의_현장_할_일을_템플릿으로_옮긴다() {
        DashboardSummaryFacts facts = DashboardSummaryFacts.from(stats(
                Map.of("IN_PROGRESS", 2, "SETTLING", 1, "BIDDING", 1),
                "11000000", "8200000", "25.45",
                site("정상현장", "IN_PROGRESS", "10000000", "7000000", "30.00"),
                site("적자현장", "SETTLING", "1000000", "1200000", "-20.00"),
                site("빈현장", "IN_PROGRESS", "0", "0", "0"),
                site("입찰현장", "BIDDING", "5000000", "0", "100.00")));

        assertThat(facts.siteNames()).containsExactly("정상현장", "적자현장", "빈현장", "입찰현장");
        assertThat(facts.deterministicSummary()).isEqualTo("""
                ### 1. 전체 현황

                - 전체 현장 4개 (수주 전 1개, 시공 중 2개, 정산 중 1개)
                - 손익 평가 대상 2개, 매입 미입력 0개, 견적 미등록 1개, 수주 전 1개

                ### 2. 손익

                - 합계(수주 전 제외): 견적 11,000,000원, 매입 8,200,000원
                - 마진(매입 입력 현장 2개 기준): 견적 11,000,000원, 매입 8,200,000원, 마진 2,800,000원, 마진율 25.45%
                - 견적 미등록 현장은 손실이 아니라 아직 데이터가 없는 상태라 손익 평가에서 뺐습니다.
                - 수주 전 현장은 계약 전 견적이라 손익 평가에서 뺐습니다.

                ### 3. 주의 현장

                - 적자현장 (정산 중): 적자 (마진 -200,000원, 마진율 -20.00%)

                ### 4. 다음 할 일

                - 적자현장: 매입 내역과 추가공사 견적 누락 여부 확인
                - 빈현장: 견적 등록""");
    }

    @Test
    void 서버_결정_문구는_주의_현장과_할_일이_없으면_없다고_쓴다() {
        DashboardSummaryFacts facts = DashboardSummaryFacts.from(stats(Map.of("IN_PROGRESS", 1), "10000000", "4000000", "60.00",
                site("정상현장", "IN_PROGRESS", "10000000", "4000000", "60.00")));

        assertThat(facts.deterministicSummary())
                .contains("### 3. 주의 현장\n\n- 주의할 현장이 없습니다.")
                .endsWith("### 4. 다음 할 일\n\n- 지금 따로 조치할 일은 없습니다.")
                .doesNotContain("견적 미등록 현장은");
    }

    @Test
    void 매입_미입력_현장만_있으면_마진율을_계산하지_않고_매입_입력을_할_일로_준다() {
        DashboardSummaryFacts facts = DashboardSummaryFacts.from(stats(Map.of("IN_PROGRESS", 1, "COMPLETED", 1),
                "7000000", "0", "100.00",
                site("가현장", "IN_PROGRESS", "3000000", "0", "100.00"),
                site("나현장", "COMPLETED", "4000000", "0", "100.00")));

        assertThat(facts.hasData()).isTrue();
        assertThat(facts.evaluatedSites()).isEmpty();
        assertThat(facts.warningSites()).isEmpty();
        assertThat(facts.noPurchaseSites()).containsExactly(
                "가현장 (시공 중): 견적 3,000,000원, 매입 미입력",
                "나현장 (완료): 견적 4,000,000원, 매입 미입력");
        assertThat(facts.averageMarginRate()).isEqualTo(DashboardSummaryFacts.NO_PURCHASE_RATE);
        assertThat(facts.nextActions()).containsExactly("매입 입력 필요(2개)");
        assertThat(facts.toPrompt())
                .contains("- 마진율: 매입이 입력된 현장이 없어 마진율을 계산하지 않음")
                .contains("- 매입 미입력 2개 현장은 마진율 계산에서 제외")
                .doesNotContain("100.00%");
        assertThat(facts.deterministicSummary())
                .contains("- 손익 평가 대상 0개, 매입 미입력 2개, 견적 미등록 0개, 수주 전 0개")
                .contains("- 합계(수주 전 제외): 견적 7,000,000원, 매입 미입력\n- 마진율: 매입이 입력된 현장이 없어 마진율을 계산하지 않음\n"
                        + "- 매입 미입력 2개 현장은 마진율 계산에서 제외")
                .contains("### 3. 주의 현장\n\n- 적자·저마진으로 판정된 현장은 없습니다. 매입 미입력 2개 현장은 매입을 입력해야 손익을 판단할 수 있습니다.")
                .endsWith("### 4. 다음 할 일\n\n- 매입 입력 필요(2개)")
                .doesNotContain("주의할 현장이 없습니다")
                .doesNotContain("매입 0원")
                .doesNotContain("100.00%");
    }

    @Test
    void 정상_현장만_있으면_주의_현장은_없음으로_넘긴다() {
        DashboardSummaryFacts facts = DashboardSummaryFacts.from(stats(Map.of("IN_PROGRESS", 1), "10000000", "4000000", "60.00",
                site("정상현장", "IN_PROGRESS", "10000000", "4000000", "60.00")));

        assertThat(facts.warningSites()).isEmpty();
        assertThat(facts.nextActions()).isEmpty();
        assertThat(facts.toPrompt())
                .contains("## 주의 현장 (서버 판정: 적자 또는 마진율 10% 미만)\n\n- 없음")
                .contains("## 다음 할 일 (서버 판정, 이 목록만 옮길 것)\n\n- 없음");
    }

    @Test
    void 마진율_10퍼센트_정확히는_주의가_아니고_소수_금액은_로케일과_무관하게_점_소수점이다() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            DashboardSummaryFacts facts = DashboardSummaryFacts.from(stats(Map.of("IN_PROGRESS", 1), "1000.50", "900.45", "10.00",
                    site("경계현장", "IN_PROGRESS", "1000.50", "900.45", "10.00")));

            assertThat(facts.warningSites()).isEmpty();
            assertThat(facts.evaluatedSites()).containsExactly(
                    "경계현장 (시공 중): 견적 1,000.5원, 매입 900.45원, 마진 100.05원, 마진율 10.00%");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void 상태별_건수는_수주_전부터_한국어_라벨로_적고_모르는_값은_원문을_남긴다() {
        assertThat(DashboardSummaryFacts.statusCounts(Map.of("COMPLETED", 1, "BIDDING", 2, "IN_PROGRESS", 3, "LEGACY", 4)))
                .isEqualTo("수주 전 2개, 시공 중 3개, 완료 1개, LEGACY 4개");
        assertThat(DashboardSummaryFacts.statusCounts(Map.of())).isEqualTo("없음");
    }
}
