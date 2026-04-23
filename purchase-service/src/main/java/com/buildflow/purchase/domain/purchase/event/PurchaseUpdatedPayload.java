package com.buildflow.purchase.domain.purchase.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseUpdatedPayload {

    private Long purchaseId;
    private Long siteId;
    private BigDecimal oldTotalAmount;
    private BigDecimal newTotalAmount;
}
