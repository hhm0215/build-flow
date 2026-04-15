package com.buildflow.tax.domain.taxinvoice.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
public class PaymentConfirmRequest {

    private LocalDate paymentDate;
}
