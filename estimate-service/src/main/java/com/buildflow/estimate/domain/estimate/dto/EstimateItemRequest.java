package com.buildflow.estimate.domain.estimate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
public class EstimateItemRequest {

    @NotBlank(message = "항목명은 필수입니다.")
    private String itemName;

    @NotBlank(message = "단위는 필수입니다.")
    private String unit;

    @NotNull(message = "수량은 필수입니다.")
    @Positive(message = "수량은 0보다 커야 합니다.")
    private BigDecimal quantity;

    @NotNull(message = "단가는 필수입니다.")
    @PositiveOrZero(message = "단가는 0 이상이어야 합니다.")
    private BigDecimal unitPrice;
}
