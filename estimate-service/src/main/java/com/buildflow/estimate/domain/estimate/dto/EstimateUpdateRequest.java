package com.buildflow.estimate.domain.estimate.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Getter
@NoArgsConstructor
public class EstimateUpdateRequest {

    @NotBlank(message = "견적서명은 필수입니다.")
    private String title;

    @NotNull(message = "견적일은 필수입니다.")
    private LocalDate estimateDate;

    @NotEmpty(message = "견적 항목은 최소 1개 이상 필요합니다.")
    @Valid
    private List<EstimateItemRequest> items;

    private String memo;
}
