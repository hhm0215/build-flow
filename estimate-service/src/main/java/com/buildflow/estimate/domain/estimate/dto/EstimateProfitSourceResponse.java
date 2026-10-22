package com.buildflow.estimate.domain.estimate.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * site-service 손익 재계산(POST /api/v1/sites/{id}/profit/reconcile)용 원천 스냅샷.
 * 확정 견적 합계와 그 합계에 이미 반영된 손익 이벤트 eventId 목록을 같은 읽기 트랜잭션에서 만든다.
 */
@Getter
@Builder
public class EstimateProfitSourceResponse {

    private Long siteId;
    /** CONFIRMED 견적 금액 합계(DRAFT·SUPERSEDED 제외). */
    private BigDecimal confirmedTotalAmount;
    private int confirmedEstimateCount;
    /** 이 현장 견적의 estimate.parsed/estimate.deleted outbox 이벤트(발행 상태 무관). */
    private List<ProfitEvent> profitEvents;

    @Getter
    @Builder
    public static class ProfitEvent {
        private String eventId;
        /** ESTIMATE_PARSED 또는 ESTIMATE_DELETED. */
        private String eventType;
    }
}
