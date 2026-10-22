package com.buildflow.chat.infra.feign.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 견적 목록에서 채팅 도구가 쓰는 필드만 받는다(항목 상세는 받지 않는다). */
@Getter
@Setter
@NoArgsConstructor
public class EstimateSummaryDto {
    private Long id;
    private String title;
    private String status;
    private LocalDate estimateDate;
    private BigDecimal totalAmount;
}
