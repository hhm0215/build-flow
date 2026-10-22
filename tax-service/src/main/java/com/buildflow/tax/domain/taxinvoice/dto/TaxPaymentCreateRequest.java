package com.buildflow.tax.domain.taxinvoice.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@NoArgsConstructor
public class TaxPaymentCreateRequest {

    @NotNull(message = "입금액은 필수입니다.")
    @Positive(message = "입금액은 0보다 커야 합니다.")
    @DecimalMax(value = "9999999999999.99", message = "입금액은 정수 13자리·소수 2자리 이하여야 합니다.")
    @Digits(integer = 13, fraction = 2, message = "입금액은 정수 13자리·소수 2자리 이하여야 합니다.")
    private BigDecimal amount;

    @NotNull(message = "입금일은 필수입니다.")
    private LocalDate paymentDate;

    @Size(max = 500, message = "입금 메모는 500자 이하여야 합니다.")
    private String memo;
}
