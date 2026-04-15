package com.buildflow.tax.domain.taxinvoice.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Builder
public class OutstandingResponse {

    private Long siteId;
    private BigDecimal totalSalesAmount;
    private BigDecimal confirmedAmount;
    private BigDecimal outstandingAmount;
    private int unpaidCount;
    private List<TaxInvoiceResponse> unpaidInvoices;
}
