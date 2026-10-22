package com.buildflow.purchase.domain.purchase.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseEvidenceLinkRequest {

    @NotNull(message = "증빙 세금계산서 ID는 필수입니다.")
    @Positive(message = "증빙 세금계산서 ID가 올바르지 않습니다.")
    private Long taxInvoiceId;

    public PurchaseEvidenceLinkRequest(Long taxInvoiceId) {
        this.taxInvoiceId = taxInvoiceId;
    }
}
