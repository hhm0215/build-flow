package com.buildflow.chat.infra.feign.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class ProfitDto {
    private Long siteId;
    private BigDecimal totalEstimateAmount;
    private BigDecimal totalPurchaseAmount;
    private BigDecimal margin;
    private BigDecimal marginRate;
}
