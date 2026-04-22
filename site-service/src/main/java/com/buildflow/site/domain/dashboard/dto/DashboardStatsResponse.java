package com.buildflow.site.domain.dashboard.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Getter
@Builder
public class DashboardStatsResponse {

    private int totalSites;
    private Map<String, Integer> sitesByStatus;
    private BigDecimal totalEstimateAmount;
    private BigDecimal totalPurchaseAmount;
    private BigDecimal totalMargin;
    private BigDecimal averageMarginRate;
    private List<SiteProfitSummary> siteProfits;

    @Getter
    @Builder
    public static class SiteProfitSummary {
        private Long siteId;
        private String siteName;
        private String status;
        private BigDecimal estimateAmount;
        private BigDecimal purchaseAmount;
        private BigDecimal margin;
        private BigDecimal marginRate;
    }
}
