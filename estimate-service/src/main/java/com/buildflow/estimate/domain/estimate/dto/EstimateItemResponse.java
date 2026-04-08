package com.buildflow.estimate.domain.estimate.dto;

import com.buildflow.estimate.domain.estimate.entity.EstimateItem;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class EstimateItemResponse {

    private Long id;
    private String itemName;
    private String unit;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private BigDecimal amount;

    public static EstimateItemResponse from(EstimateItem item) {
        return EstimateItemResponse.builder()
                .id(item.getId())
                .itemName(item.getItemName())
                .unit(item.getUnit())
                .quantity(item.getQuantity())
                .unitPrice(item.getUnitPrice())
                .amount(item.getAmount())
                .build();
    }
}
