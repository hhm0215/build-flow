package com.buildflow.site.domain.profit.dto;

import com.buildflow.site.domain.profit.entity.SiteProfit;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/** 손익 재계산 결과. 금액·건수만 담고 개인정보는 담지 않는다. */
@Getter
@Builder
public class ProfitReconcileResponse {

    private Long siteId;
    /** 요청이 apply=true였는지. */
    private boolean applyRequested;
    /** 실제로 site DB에 반영했는지(apply=true이고 차이가 있을 때만 true). */
    private boolean applied;
    /** 손익·projection·이벤트 수신기록이 모두 원천과 이미 일치하면 true. */
    private boolean matched;
    /** 재계산 실행 식별자(로그·projection lastEventId에 남는다). */
    private String reconcileId;
    private Totals before;
    /** 원천 기준 기대값. apply 반영 시 저장된 값과 같다. */
    private Totals source;
    /** 원천 합계에 이미 반영됐지만 수신기록이 없는 견적 손익 이벤트 수(apply 시 처리 완료로 기록). */
    private int estimateEventsToMark;
    /** 원천 기준으로 다시 쓸 매입 projection 수(누락·금액·revision 차이 + 원천에 없는 활성 projection). */
    private int purchaseProjectionsToRewrite;
    /** 견적 행이 없어 원천 eventId 목록에 없는 이 현장 손익 이벤트 수. 0보다 크면 apply는 409로 막힌다. */
    private int orphanEstimateEvents;

    @Getter
    @Builder
    public static class Totals {
        private BigDecimal totalEstimateAmount;
        private BigDecimal totalPurchaseAmount;
        private BigDecimal margin;
        private BigDecimal marginRate;

        public static Totals of(SiteProfit profit) {
            return Totals.builder()
                    .totalEstimateAmount(profit.getTotalEstimateAmount())
                    .totalPurchaseAmount(profit.getTotalPurchaseAmount())
                    .margin(profit.getMargin())
                    .marginRate(profit.getMarginRate())
                    .build();
        }

        public boolean sameAs(Totals other) {
            return totalEstimateAmount.compareTo(other.totalEstimateAmount) == 0
                    && totalPurchaseAmount.compareTo(other.totalPurchaseAmount) == 0
                    && margin.compareTo(other.margin) == 0
                    && marginRate.compareTo(other.marginRate) == 0;
        }
    }
}
