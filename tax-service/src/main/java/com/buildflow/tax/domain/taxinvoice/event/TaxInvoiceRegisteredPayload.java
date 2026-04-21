package com.buildflow.tax.domain.taxinvoice.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxInvoiceRegisteredPayload {

    private Long taxInvoiceId;
    private Long siteId;
    private String type;
    private BigDecimal totalAmount;
    private String counterparty;
}
