package com.buildflow.notification.domain.warranty.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
public class WarrantyUpdateRequest {

    @NotBlank(message = "보험사는 필수입니다.")
    private String insuranceCompany;

    private String policyNumber;

    @NotNull(message = "보험 시작일은 필수입니다.")
    private LocalDate startDate;

    @NotNull(message = "보험 만료일은 필수입니다.")
    private LocalDate endDate;

    private String memo;
}
