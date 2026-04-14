package com.buildflow.site.domain.profit.event;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class EstimateParsedPayload {

    private Long estimateId;
    private Long siteId;
    private BigDecimal totalAmount;
}
