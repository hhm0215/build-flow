package com.buildflow.site.infra.feign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * estimate-service GET /api/v1/estimates/profit-source 응답 data.
 * orphanProfitEventCount는 견적 행이 없어 목록에 넣지 못한 손익 이벤트 수(없으면 원천 형식 이상으로 본다).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EstimateProfitSource(Long siteId, BigDecimal confirmedTotalAmount, List<Event> profitEvents,
                                   Integer orphanProfitEventCount) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(String eventId, String eventType) {
    }
}
