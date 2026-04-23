package com.buildflow.site.domain.dashboard.service;

import com.buildflow.site.domain.dashboard.dto.DashboardStatsResponse;
import com.buildflow.site.domain.dashboard.dto.DashboardStatsResponse.SiteProfitSummary;
import com.buildflow.site.domain.dashboard.dto.DashboardSummaryResponse;
import com.buildflow.site.domain.profit.entity.SiteProfit;
import com.buildflow.site.domain.profit.repository.SiteProfitRepository;
import com.buildflow.site.domain.site.entity.Site;
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
        List<SiteProfit> profits = siteProfitRepository.findAll();

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
                .collect(Collectors.toList());

        for (SiteProfit profit : profits) {
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
        DashboardStatsResponse stats = getStats();
        String summary = ollamaService.generateSummary(stats);

        return DashboardSummaryResponse.builder()
                .summary(summary)
                .generatedAt(LocalDateTime.now())
                .build();
    }
}
