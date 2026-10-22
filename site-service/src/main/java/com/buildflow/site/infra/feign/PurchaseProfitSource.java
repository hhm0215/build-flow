package com.buildflow.site.infra.feign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/** purchase-service GET /api/v1/purchases/profit-source 응답 data. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PurchaseProfitSource(Long siteId, BigDecimal totalAmount, List<Item> purchases) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(Long purchaseId, Long revision, BigDecimal totalAmount) {
    }
}
