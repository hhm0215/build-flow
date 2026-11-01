package com.buildflow.purchase.domain.purchase.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseDeletedPayload {

    private Long purchaseId;
    private Long siteId;
    private Long revision;
    private BigDecimal totalAmount;

    /** 알림 문구용 선택 필드(2026-11-01 추가). null이면 직렬화하지 않아 구 이벤트 형태를 유지한다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String itemName;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String supplier;
}
