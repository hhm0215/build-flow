package com.buildflow.purchase.domain.purchase.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@NoArgsConstructor
public class PurchaseCreateRequest {

    @NotNull(message = "현장 ID는 필수입니다.")
    private Long siteId;

    @NotBlank(message = "품목명은 필수입니다.")
    private String itemName;

    @NotNull(message = "수량은 필수입니다.")
    @Min(value = 1, message = "수량은 1 이상이어야 합니다.")
    private Integer quantity;

    @NotNull(message = "단가는 필수입니다.")
    private BigDecimal unitPrice;

    private String supplier;
    private LocalDate purchaseDate;
    private String memo;
}
