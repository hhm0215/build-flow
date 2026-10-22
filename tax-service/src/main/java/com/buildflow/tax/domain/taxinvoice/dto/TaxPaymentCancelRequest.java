package com.buildflow.tax.domain.taxinvoice.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class TaxPaymentCancelRequest {

    @Size(max = 500, message = "취소 사유는 500자 이하여야 합니다.")
    private String reason;
}
