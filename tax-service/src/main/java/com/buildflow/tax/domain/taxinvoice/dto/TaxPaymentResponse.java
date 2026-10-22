package com.buildflow.tax.domain.taxinvoice.dto;

import com.buildflow.tax.domain.taxinvoice.entity.TaxPayment;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
public class TaxPaymentResponse {

    private Long id;
    private Long taxInvoiceId;
    private BigDecimal amount;
    private LocalDate paymentDate;
    private String memo;
    private boolean canceled;
    private LocalDateTime canceledAt;
    private String cancelReason;
    private LocalDateTime createdAt;

    public static TaxPaymentResponse from(TaxPayment payment) {
        return TaxPaymentResponse.builder()
                .id(payment.getId())
                .taxInvoiceId(payment.getTaxInvoiceId())
                .amount(payment.getAmount())
                .paymentDate(payment.getPaymentDate())
                .memo(payment.getMemo())
                .canceled(payment.isCanceled())
                .canceledAt(payment.getCanceledAt())
                .cancelReason(payment.getCancelReason())
                .createdAt(payment.getCreatedAt())
                .build();
    }
}
