package com.buildflow.estimate.domain.estimate.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EstimateParsedPayload {

    private Long estimateId;
    private Long siteId;
    private BigDecimal totalAmount;
}
