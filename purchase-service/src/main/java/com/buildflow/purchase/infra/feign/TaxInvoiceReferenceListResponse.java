package com.buildflow.purchase.infra.feign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TaxInvoiceReferenceListResponse(boolean success, List<TaxInvoiceReference> data) {
}
