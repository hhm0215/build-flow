package com.buildflow.purchase.domain.purchase.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@NoArgsConstructor
public class PurchaseCreateRequest {

    @NotNull(message = "현장 ID는 필수입니다.")
    @Positive(message = "현장 ID는 양수여야 합니다.")
    private Long siteId;

    @NotBlank(message = "품목명은 필수입니다.")
    @Size(max = 200, message = "품목명은 200자 이하여야 합니다.")
    private String itemName;

    @NotNull(message = "수량은 필수입니다.")
    @Positive(message = "수량은 1 이상이어야 합니다.")
    private Integer quantity;

    @NotNull(message = "단가는 필수입니다.")
    @PositiveOrZero(message = "단가는 0 이상이어야 합니다.")
    @Digits(integer = 10, fraction = 2, message = "단가는 정수 10자리·소수 2자리 이하여야 합니다.")
    private BigDecimal unitPrice;

    @Size(max = 200, message = "공급업체명은 200자 이하여야 합니다.")
    private String supplier;
    private LocalDate purchaseDate;
    private String memo;
}
