package com.buildflow.chat.infra.feign.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** site-service {@code GET /api/v1/dashboard/stats} 응답. 합계 필드는 수주 전(BIDDING) 현장을 제외한 값이다. */
@Getter
@Setter
@NoArgsConstructor
public class DashboardStatsDto {
    private int totalSites;
    private Map<String, Integer> sitesByStatus;
    private BigDecimal totalEstimateAmount;
    private BigDecimal totalPurchaseAmount;
    private BigDecimal totalMargin;
    private BigDecimal averageMarginRate;
    private List<SiteProfitDto> siteProfits;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class SiteProfitDto {
        private Long siteId;
        private String siteName;
        private String status;
        private BigDecimal estimateAmount;
        private BigDecimal purchaseAmount;
        private BigDecimal margin;
        private BigDecimal marginRate;
    }
}
