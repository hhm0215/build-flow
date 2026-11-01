package com.buildflow.site.domain.dashboard.service;

import com.buildflow.site.domain.dashboard.dto.DashboardStatsResponse;
import com.buildflow.site.domain.dashboard.dto.DashboardStatsResponse.SiteProfitSummary;
import com.buildflow.site.domain.dashboard.dto.DashboardSummaryResponse;
import com.buildflow.site.domain.profit.entity.SiteProfit;
import com.buildflow.site.domain.profit.repository.SiteProfitRepository;
import com.buildflow.site.domain.site.entity.Site;
import com.buildflow.site.domain.site.entity.SiteStatus;
import com.buildflow.site.domain.site.repository.SiteRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 대시보드 전체 손익은 현장별 손익 합계로 계산하고, 손익 기록이 없는 현장은 0으로 보여 준다. */
class DashboardServiceTest {

    private final SiteRepository sites = mock(SiteRepository.class);
    private final SiteProfitRepository profits = mock(SiteProfitRepository.class);
    private final OllamaService ollama = mock(OllamaService.class);
    private final DashboardService service = new DashboardService(sites, profits, ollama);

    private static Site site(long id, String name, SiteStatus status) {
        Site site = Site.builder().siteName(name).build();
        ReflectionTestUtils.setField(site, "id", id);
        site.changeStatus(status);
        return site;
    }

    private static SiteProfit profit(long siteId, String estimate, String purchase) {
        SiteProfit profit = SiteProfit.builder().siteId(siteId).build();
        profit.addEstimateAmount(new BigDecimal(estimate));
        profit.adjustPurchaseAmount(new BigDecimal(purchase));
        return profit;
    }

    @Test
    void 현장별_손익을_합산하고_가중_평균_마진율을_낸다() {
        when(sites.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(
                site(3, "C현장", SiteStatus.IN_PROGRESS),
                site(2, "B현장", SiteStatus.SETTLING),
                site(1, "A현장", SiteStatus.IN_PROGRESS)));
        when(profits.findBySiteIdIn(any())).thenReturn(List.of(
                profit(3, "1000", "600"),
                profit(1, "3000", "3300")));

        DashboardStatsResponse stats = service.getStats();

        assertThat(stats.getTotalSites()).isEqualTo(3);
        assertThat(stats.getSitesByStatus()).isEqualTo(Map.of("IN_PROGRESS", 2, "SETTLING", 1));
        assertThat(stats.getTotalEstimateAmount()).isEqualByComparingTo("4000");
        assertThat(stats.getTotalPurchaseAmount()).isEqualByComparingTo("3900");
        assertThat(stats.getTotalMargin()).isEqualByComparingTo("100");
        // 단순 평균((40 + -10) / 2 = 15)이 아니라 총마진/총견적 = 2.50
        assertThat(stats.getAverageMarginRate()).isEqualByComparingTo("2.50");
        assertThat(stats.getSiteProfits()).extracting(SiteProfitSummary::getSiteName)
                .containsExactly("C현장", "B현장", "A현장");
        SiteProfitSummary noProfit = stats.getSiteProfits().get(1);
        assertThat(noProfit.getEstimateAmount()).isEqualByComparingTo("0");
        assertThat(noProfit.getMargin()).isEqualByComparingTo("0");
        assertThat(noProfit.getMarginRate()).isEqualByComparingTo("0");
        assertThat(stats.getSiteProfits().get(2).getMargin()).isEqualByComparingTo("-300");
    }

    @Test
    void 수주_전_현장의_손익은_전체_합계에서_빼고_목록에는_남긴다() {
        when(sites.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(
                site(2, "입찰현장", SiteStatus.BIDDING),
                site(1, "A현장", SiteStatus.IN_PROGRESS)));
        when(profits.findBySiteIdIn(any())).thenReturn(List.of(
                profit(2, "5000", "0"),
                profit(1, "1000", "800")));

        DashboardStatsResponse stats = service.getStats();

        assertThat(stats.getTotalEstimateAmount()).isEqualByComparingTo("1000");
        assertThat(stats.getTotalPurchaseAmount()).isEqualByComparingTo("800");
        assertThat(stats.getTotalMargin()).isEqualByComparingTo("200");
        assertThat(stats.getAverageMarginRate()).isEqualByComparingTo("20.00");
        assertThat(stats.getSitesByStatus()).isEqualTo(Map.of("BIDDING", 1, "IN_PROGRESS", 1));
        assertThat(stats.getSiteProfits().get(0).getEstimateAmount()).isEqualByComparingTo("5000");
    }

    @Test
    void 현장이_없거나_견적이_0이면_마진율은_0이다() {
        when(sites.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
        when(profits.findBySiteIdIn(any())).thenReturn(List.of());

        DashboardStatsResponse empty = service.getStats();

        assertThat(empty.getTotalSites()).isZero();
        assertThat(empty.getSitesByStatus()).isEmpty();
        assertThat(empty.getAverageMarginRate()).isEqualByComparingTo("0");

        when(sites.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(site(1, "A현장", SiteStatus.IN_PROGRESS)));
        when(profits.findBySiteIdIn(any())).thenReturn(List.of(profit(1, "0", "500")));

        DashboardStatsResponse loss = service.getStats();

        assertThat(loss.getTotalMargin()).isEqualByComparingTo("-500");
        assertThat(loss.getAverageMarginRate()).isEqualByComparingTo("0");
    }

    @Test
    void 현장이_없으면_모델을_부르지_않고_결정적_안내를_준다() {
        when(sites.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
        when(profits.findBySiteIdIn(any())).thenReturn(List.of());

        DashboardSummaryResponse summary = service.getSummary();

        assertThat(summary.getSummary()).isEqualTo(DashboardSummaryFacts.NO_SITE_MESSAGE);
        assertThat(summary.getGeneratedAt()).isNotNull();
        verifyNoInteractions(ollama);
    }

    @Test
    void 견적_매입이_없는_현장만_있으면_모델을_부르지_않고_손실로_말하지_않는다() {
        when(sites.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(site(1, "[QA] AI 요약 현장", SiteStatus.IN_PROGRESS)));
        when(profits.findBySiteIdIn(any())).thenReturn(List.of());

        DashboardSummaryResponse summary = service.getSummary();

        assertThat(summary.getSummary()).startsWith(DashboardSummaryFacts.NO_DATA_MESSAGE_PREFIX).doesNotContain("적자");
        verifyNoInteractions(ollama);
    }

    @Test
    void 데이터가_있으면_서버가_분류한_사실을_모델에_넘기고_생성_시각을_붙인다() {
        when(sites.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(
                site(2, "빈현장", SiteStatus.IN_PROGRESS),
                site(1, "적자현장", SiteStatus.IN_PROGRESS)));
        when(profits.findBySiteIdIn(any())).thenReturn(List.of(profit(1, "1000", "1200")));
        when(ollama.generateSummary(any())).thenReturn("요약");

        DashboardSummaryResponse summary = service.getSummary();

        assertThat(summary.getSummary()).isEqualTo("요약");
        assertThat(summary.getGeneratedAt()).isNotNull();
        ArgumentCaptor<DashboardSummaryFacts> captor = ArgumentCaptor.forClass(DashboardSummaryFacts.class);
        verify(ollama).generateSummary(captor.capture());
        DashboardSummaryFacts facts = captor.getValue();
        assertThat(facts.warningSites()).containsExactly("적자현장 (시공 중): 적자 (마진 -200원, 마진율 -20.00%)");
        assertThat(facts.noEstimateSites()).containsExactly("빈현장 (시공 중): 견적·매입 미등록");
    }
}
