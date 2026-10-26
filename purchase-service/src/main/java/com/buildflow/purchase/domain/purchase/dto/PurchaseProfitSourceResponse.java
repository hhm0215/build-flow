package com.buildflow.purchase.domain.purchase.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * site-service 손익 재계산(POST /api/v1/sites/{id}/profit/reconcile)용 원천 스냅샷.
 * 현재 매입 행·revision과 삭제된 매입을 포함한 손익 이벤트를 같은 읽기 트랜잭션에서 돌려준다.
 */
@Getter
@Builder
public class PurchaseProfitSourceResponse {

    private Long siteId;
    private BigDecimal totalAmount;
    private List<Item> purchases;
    @Builder.Default
    private List<ProfitEvent> profitEvents = List.of();

    @Getter
    @Builder
    public static class ProfitEvent {
        private String eventId;
        private String eventType;
    }

    @Getter
    @Builder
    public static class Item {
        private Long purchaseId;
        /** 마지막으로 발행한 손익 이벤트의 revision(purchases.event_revision). */
        private long revision;
        private BigDecimal totalAmount;
    }
}
