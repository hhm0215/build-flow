package com.buildflow.tax.domain.taxinvoice.dto;

import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoiceType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@NoArgsConstructor
public class TaxInvoiceUpdateRequest {

    @NotNull(message = "구분(매출/매입)은 필수입니다.")
    private TaxInvoiceType type;

    @NotNull(message = "공급가액은 필수입니다.")
    @PositiveOrZero(message = "공급가액은 0 이상이어야 합니다.")
    @Digits(integer = 13, fraction = 2,
            message = "공급가액은 정수 13자리·소수 2자리 이하여야 합니다.")
    private BigDecimal supplyAmount;

    @NotNull(message = "세액은 필수입니다.")
    @PositiveOrZero(message = "세액은 0 이상이어야 합니다.")
    @Digits(integer = 13, fraction = 2,
            message = "세액은 정수 13자리·소수 2자리 이하여야 합니다.")
    private BigDecimal taxAmount;

    @Size(max = 200, message = "거래처는 200자 이하여야 합니다.")
    private String counterparty;
    private LocalDate issueDate;
    private String memo;
}
