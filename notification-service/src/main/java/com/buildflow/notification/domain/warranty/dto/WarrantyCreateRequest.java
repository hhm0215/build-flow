package com.buildflow.notification.domain.warranty.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
public class WarrantyCreateRequest {

    @NotNull(message = "현장 ID는 필수입니다.")
    private Long siteId;

    @NotBlank(message = "보험사는 필수입니다.")
    @Size(max = 200, message = "보험사는 200자 이하여야 합니다.")
    private String insuranceCompany;

    @Size(max = 100, message = "증권번호는 100자 이하여야 합니다.")
    private String policyNumber;

    @NotNull(message = "보험 시작일은 필수입니다.")
    private LocalDate startDate;

    @NotNull(message = "보험 만료일은 필수입니다.")
    private LocalDate endDate;

    /** 보증금액 (원). 선택 — 추후 추가 가능 */
    private Long coverageAmount;

    // Java UTF-16 16,000 code units는 utf8mb4 최악에도 64,000 bytes로 MySQL TEXT 한도 이내다.
    @Size(max = 16000, message = "메모는 16000자 이하여야 합니다.")
    private String memo;
}
