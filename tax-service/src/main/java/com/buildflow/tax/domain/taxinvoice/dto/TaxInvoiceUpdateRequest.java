package com.buildflow.tax.domain.taxinvoice.dto;

import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoiceType;
import jakarta.validation.constraints.NotNull;
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
    private BigDecimal supplyAmount;

    @NotNull(message = "세액은 필수입니다.")
    private BigDecimal taxAmount;

    private String counterparty;
    private LocalDate issueDate;
    private String memo;
}
