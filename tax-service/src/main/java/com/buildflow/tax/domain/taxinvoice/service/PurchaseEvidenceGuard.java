package com.buildflow.tax.domain.taxinvoice.service;

import com.buildflow.tax.global.exception.BusinessException;
import com.buildflow.tax.global.exception.ErrorCode;
import com.buildflow.tax.infra.feign.PurchaseEvidenceClient;
import com.buildflow.tax.infra.feign.PurchaseEvidenceLinkResponse;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 매입 항목의 증빙으로 연결된 매입 세금계산서는 삭제하거나 매출로 바꿀 수 없다(409, 연결 해제 후 가능).
 * purchase-service를 확인할 수 없으면 503으로 실패-닫힘.
 */
@Component
@RequiredArgsConstructor
public class PurchaseEvidenceGuard {

    private final PurchaseEvidenceClient purchaseEvidenceClient;

    public void requireNoLinkedPurchases(Long taxInvoiceId) {
        PurchaseEvidenceLinkResponse response;
        try {
            response = purchaseEvidenceClient.countLinks(taxInvoiceId);
        } catch (FeignException e) {
            throw new BusinessException(ErrorCode.PURCHASE_REFERENCE_UNAVAILABLE);
        }
        if (response == null || !response.success() || response.data() == null
                || response.data().linkedPurchaseCount() == null
                || !taxInvoiceId.equals(response.data().taxInvoiceId())) {
            throw new BusinessException(ErrorCode.PURCHASE_REFERENCE_UNAVAILABLE);
        }
        if (response.data().linkedPurchaseCount() > 0) {
            throw new BusinessException(ErrorCode.TAX_INVOICE_LINKED_TO_PURCHASES);
        }
    }
}
