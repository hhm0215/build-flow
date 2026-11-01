package com.buildflow.estimate.domain.estimate.event;

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
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EstimateParsedPayload {

    public static final String REASON_SUPERSEDED = "SUPERSEDED";

    private Long estimateId;
    private Long siteId;
    private BigDecimal totalAmount;

    /** estimate.deleted 전용 선택 필드. 수정본 확정으로 손익에서 빠질 때 "SUPERSEDED". */
    private String reason;

    /** reason=SUPERSEDED일 때 이 견적을 대체한 수정본 id. */
    private Long supersededById;

    /** 알림 문구용 선택 필드(2026-11-01 추가). 구 소비자는 무시하고, 없는 구 이벤트는 ID 문구로 폴백한다. */
    private String title;
}
