package com.buildflow.purchase.infra.feign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;

/** tax-service 세금계산서 응답 중 증빙 대조에 필요한 필드만 읽는다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaxInvoiceReference(Long id, Long siteId, String type,
                                  BigDecimal supplyAmount, BigDecimal taxAmount, BigDecimal totalAmount,
                                  String counterparty, LocalDate issueDate) {

    public static final String PURCHASE_TYPE = "PURCHASE";

    public boolean isPurchase() {
        return PURCHASE_TYPE.equals(type);
    }
}
