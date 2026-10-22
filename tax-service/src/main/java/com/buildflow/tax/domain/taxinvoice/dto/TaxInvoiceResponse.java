package com.buildflow.tax.domain.taxinvoice.dto;

import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoice;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoiceType;
import com.buildflow.tax.domain.taxinvoice.entity.TaxPaymentStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
public class TaxInvoiceResponse {

    private Long id;
    private Long siteId;
    private TaxInvoiceType type;
    private BigDecimal supplyAmount;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private String counterparty;
    private LocalDate issueDate;
    /** 전액 입금 완료 여부(유효 입금 합계 = 총액). */
    private boolean paymentConfirmed;
    /** 완료 시 마지막 유효 입금일, 미완료면 null. */
    private LocalDate paymentDate;
    /** 유효 입금 합계. 매입 세금계산서는 수금 대상이 아니므로 null. */
    private BigDecimal paidAmount;
    /** 공급가+세액 − 유효 입금 합계. 매입은 null. */
    private BigDecimal remainingAmount;
    /** 매출만 UNPAID/PARTIAL/PAID, 매입은 null. */
    private TaxPaymentStatus paymentStatus;
    private String memo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * @param paidAmount 유효 입금 합계(매출). 매입이면 무시한다.
     */
    public static TaxInvoiceResponse from(TaxInvoice taxInvoice, BigDecimal paidAmount) {
        boolean sales = taxInvoice.getType() == TaxInvoiceType.SALES;
        BigDecimal paid = sales ? (paidAmount == null ? BigDecimal.ZERO : paidAmount) : null;
        BigDecimal remaining = sales ? taxInvoice.getTotalAmount().subtract(paid) : null;
        TaxPaymentStatus status = sales ? statusOf(taxInvoice, paid) : null;
        return TaxInvoiceResponse.builder()
                .id(taxInvoice.getId())
                .siteId(taxInvoice.getSiteId())
                .type(taxInvoice.getType())
                .supplyAmount(taxInvoice.getSupplyAmount())
                .taxAmount(taxInvoice.getTaxAmount())
                .totalAmount(taxInvoice.getTotalAmount())
                .counterparty(taxInvoice.getCounterparty())
                .issueDate(taxInvoice.getIssueDate())
                .paymentConfirmed(taxInvoice.isPaymentConfirmed())
                .paymentDate(taxInvoice.getPaymentDate())
                .paidAmount(paid)
                .remainingAmount(remaining)
                .paymentStatus(status)
                .memo(taxInvoice.getMemo())
                .createdAt(taxInvoice.getCreatedAt())
                .updatedAt(taxInvoice.getUpdatedAt())
                .build();
    }

    private static TaxPaymentStatus statusOf(TaxInvoice taxInvoice, BigDecimal paid) {
        if (taxInvoice.isPaymentConfirmed()) {
            return TaxPaymentStatus.PAID;
        }
        return paid.signum() > 0 ? TaxPaymentStatus.PARTIAL : TaxPaymentStatus.UNPAID;
    }
}
