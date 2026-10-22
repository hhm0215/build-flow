package com.buildflow.site.infra.feign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/** estimate-service GET /api/v1/estimates/profit-source 응답 data. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EstimateProfitSource(Long siteId, BigDecimal confirmedTotalAmount, List<Event> profitEvents) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(String eventId, String eventType) {
    }
}
