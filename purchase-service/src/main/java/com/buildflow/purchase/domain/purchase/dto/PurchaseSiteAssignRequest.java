package com.buildflow.purchase.domain.purchase.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PurchaseSiteAssignRequest {

    @NotNull(message = "현장 ID는 필수입니다.")
    @Positive(message = "현장 ID는 양수여야 합니다.")
    private Long siteId;
}
