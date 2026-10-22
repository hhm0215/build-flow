package com.buildflow.estimate.domain.estimate.dto;

import com.buildflow.estimate.domain.estimate.entity.EstimateKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Getter
@NoArgsConstructor
public class EstimateCreateRequest {

    @NotNull(message = "현장 ID는 필수입니다.")
    private Long siteId;

    @NotBlank(message = "견적서명은 필수입니다.")
    @Size(max = 200, message = "견적서명은 200자 이하여야 합니다.")
    private String title;

    @NotNull(message = "견적일은 필수입니다.")
    private LocalDate estimateDate;

    @NotEmpty(message = "견적 항목은 최소 1개 이상 필요합니다.")
    @Valid
    private List<EstimateItemRequest> items;

    @Size(max = 1000, message = "메모는 1000자 이하여야 합니다.")
    private String memo;

    /** 생략하면 ORIGINAL. REVISION·ADDITIONAL은 revisionOfId가 필수. */
    private EstimateKind kind;

    /** 같은 현장의 확정(또는 대체된) 원 견적 id. */
    @Positive(message = "원 견적 ID는 양수여야 합니다.")
    private Long revisionOfId;
}
