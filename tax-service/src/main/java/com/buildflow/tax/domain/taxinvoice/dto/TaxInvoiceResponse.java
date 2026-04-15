package com.buildflow.tax.domain.taxinvoice.dto;

import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoice;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoiceType;
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
    private boolean paymentConfirmed;
    private LocalDate paymentDate;
    private String memo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static TaxInvoiceResponse from(TaxInvoice taxInvoice) {
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
                .memo(taxInvoice.getMemo())
                .createdAt(taxInvoice.getCreatedAt())
                .updatedAt(taxInvoice.getUpdatedAt())
                .build();
    }
}
