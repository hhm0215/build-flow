package com.buildflow.chat.infra.feign.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class OutstandingDto {
    private Long siteId;
    private BigDecimal totalSalesAmount;
    private BigDecimal confirmedAmount;
    private BigDecimal outstandingAmount;
    private int unpaidCount;
}
