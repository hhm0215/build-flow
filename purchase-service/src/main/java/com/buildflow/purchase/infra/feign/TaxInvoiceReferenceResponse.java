package com.buildflow.purchase.infra.feign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TaxInvoiceReferenceResponse(boolean success, TaxInvoiceReference data) {
}
