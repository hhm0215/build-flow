package com.buildflow.tax.infra.feign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PurchaseEvidenceLinkResponse(boolean success, LinkCount data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LinkCount(Long taxInvoiceId, Long linkedPurchaseCount) {
    }
}
