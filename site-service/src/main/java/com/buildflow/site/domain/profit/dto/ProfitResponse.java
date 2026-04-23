package com.buildflow.site.domain.profit.dto;

import com.buildflow.site.domain.profit.entity.SiteProfit;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class ProfitResponse {

    private Long siteId;
    private BigDecimal totalEstimateAmount;
    private BigDecimal totalPurchaseAmount;
    private BigDecimal margin;
    private BigDecimal marginRate;

    public static ProfitResponse from(SiteProfit profit) {
        return ProfitResponse.builder()
                .siteId(profit.getSiteId())
                .totalEstimateAmount(profit.getTotalEstimateAmount())
                .totalPurchaseAmount(profit.getTotalPurchaseAmount())
                .margin(profit.getMargin())
                .marginRate(profit.getMarginRate())
                .build();
    }

    public static ProfitResponse empty(Long siteId) {
        return ProfitResponse.builder()
                .siteId(siteId)
                .totalEstimateAmount(BigDecimal.ZERO)
                .totalPurchaseAmount(BigDecimal.ZERO)
                .margin(BigDecimal.ZERO)
                .marginRate(BigDecimal.ZERO)
                .build();
    }
}
