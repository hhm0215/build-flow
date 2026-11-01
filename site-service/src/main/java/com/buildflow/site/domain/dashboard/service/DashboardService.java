package com.buildflow.site.domain.dashboard.service;

import com.buildflow.site.domain.dashboard.dto.DashboardStatsResponse;
import com.buildflow.site.domain.dashboard.dto.DashboardStatsResponse.SiteProfitSummary;
import com.buildflow.site.domain.dashboard.dto.DashboardSummaryResponse;
import com.buildflow.site.domain.profit.entity.SiteProfit;
import com.buildflow.site.domain.profit.repository.SiteProfitRepository;
import com.buildflow.site.domain.site.entity.Site;
import com.buildflow.site.domain.site.entity.SiteStatus;
import com.buildflow.site.domain.site.repository.SiteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final SiteRepository siteRepository;
    private final SiteProfitRepository siteProfitRepository;
    private final OllamaService ollamaService;

    public DashboardStatsResponse getStats() {
        List<Site> sites = siteRepository.findAllByOrderByCreatedAtDesc();

        Set<Long> siteIds = sites.stream()
                .map(Site::getId)
                .collect(Collectors.toSet());

        List<SiteProfit> profits = siteProfitRepository.findBySiteIdIn(siteIds);

        Map<Long, SiteProfit> profitMap = profits.stream()
                .collect(Collectors.toMap(SiteProfit::getSiteId, p -> p));

        Map<String, Integer> sitesByStatus = sites.stream()
                .collect(Collectors.groupingBy(
                        s -> s.getStatus().name(),
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
                ));

        BigDecimal totalEstimate = BigDecimal.ZERO;
        BigDecimal totalPurchase = BigDecimal.ZERO;

        List<SiteProfitSummary> siteProfits = sites.stream()
                .map(site -> {
                    SiteProfit profit = profitMap.get(site.getId());
                    return SiteProfitSummary.builder()
                            .siteId(site.getId())
                            .siteName(site.getSiteName())
                            .status(site.getStatus().name())
                            .estimateAmount(profit != null ? profit.getTotalEstimateAmount() : BigDecimal.ZERO)
                            .purchaseAmount(profit != null ? profit.getTotalPurchaseAmount() : BigDecimal.ZERO)
                            .margin(profit != null ? profit.getMargin() : BigDecimal.ZERO)
                            .marginRate(profit != null ? profit.getMarginRate() : BigDecimal.ZERO)
                            .build();
                })
                .toList();

        // 수주 전(BIDDING) 현장의 견적은 계약 전 금액이라 전체 손익 합계에 넣지 않는다. 현장별 목록에는 그대로 보인다.
        Set<Long> biddingSiteIds = sites.stream()
                .filter(site -> site.getStatus() == SiteStatus.BIDDING)
                .map(Site::getId)
                .collect(Collectors.toSet());
        for (SiteProfit profit : profits) {
            if (biddingSiteIds.contains(profit.getSiteId())) {
                continue;
            }
            totalEstimate = totalEstimate.add(profit.getTotalEstimateAmount());
            totalPurchase = totalPurchase.add(profit.getTotalPurchaseAmount());
        }

        BigDecimal totalMargin = totalEstimate.subtract(totalPurchase);
        BigDecimal averageMarginRate = BigDecimal.ZERO;
        if (totalEstimate.compareTo(BigDecimal.ZERO) > 0) {
            averageMarginRate = totalMargin
                    .multiply(BigDecimal.valueOf(100))
                    .divide(totalEstimate, 2, RoundingMode.HALF_UP);
        }

        return DashboardStatsResponse.builder()
                .totalSites(sites.size())
                .sitesByStatus(sitesByStatus)
                .totalEstimateAmount(totalEstimate)
                .totalPurchaseAmount(totalPurchase)
                .totalMargin(totalMargin)
                .averageMarginRate(averageMarginRate)
                .siteProfits(siteProfits)
                .build();
    }

    public DashboardSummaryResponse getSummary() {
        DashboardSummaryFacts facts = DashboardSummaryFacts.from(getStats());
        // 견적·매입이 하나도 없으면 모델을 부르지 않는다(빈 현장을 손실로 오판하는 환각 방지).
        String summary = facts.hasData() ? guardedSummary(facts) : facts.noDataMessage();

        return DashboardSummaryResponse.builder()
                .summary(summary)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 모델 출력을 사실 목록과 대조해 외국 문자·모르는 현장명·새 금액·할 일 모순이 있으면 서버 결정 문구로 바꾼다.
     * 모델 호출 실패(503)는 그대로 전파한다. 화면이 오류·재시도를 보여 주므로 장애를 정상 요약으로 숨기지 않는다.
     */
    private String guardedSummary(DashboardSummaryFacts facts) {
        String output = ollamaService.generateSummary(facts);
        List<String> violations = SummaryOutputGuard.violations(output, facts);
        if (violations.isEmpty()) {
            return output;
        }
        // 출력 원문은 사용자 입력(현장명)이 섞일 수 있어 남기지 않는다. 길이와 위반 종류만.
        log.warn("AI 요약 출력 검증 실패, 서버 결정 문구로 대체: 사유={}, 출력길이={}",
                violations, output == null ? 0 : output.length());
        return facts.deterministicSummary();
    }
}
