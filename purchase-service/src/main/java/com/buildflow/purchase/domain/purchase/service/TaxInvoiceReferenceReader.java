package com.buildflow.purchase.domain.purchase.service;

import com.buildflow.purchase.global.exception.BusinessException;
import com.buildflow.purchase.global.exception.ErrorCode;
import com.buildflow.purchase.infra.feign.TaxInvoiceReference;
import com.buildflow.purchase.infra.feign.TaxInvoiceReferenceClient;
import com.buildflow.purchase.infra.feign.TaxInvoiceReferenceListResponse;
import com.buildflow.purchase.infra.feign.TaxInvoiceReferenceResponse;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * tax-service 세금계산서 조회. 확인할 수 없으면 연결·대조를 진행하지 않고 503으로 실패-닫힘.
 */
@Component
@RequiredArgsConstructor
public class TaxInvoiceReferenceReader {

    private final TaxInvoiceReferenceClient client;

    /** 같은 현장의 매입 세금계산서인지 확인한다. 없음·매출·다른 현장은 400. */
    public TaxInvoiceReference requirePurchaseInvoiceOfSite(Long taxInvoiceId, Long siteId) {
        if (taxInvoiceId == null || taxInvoiceId <= 0) {
            throw new BusinessException(ErrorCode.EVIDENCE_TAX_INVOICE_NOT_FOUND);
        }
        TaxInvoiceReferenceResponse response;
        try {
            response = client.findById(taxInvoiceId);
        } catch (FeignException.NotFound e) {
            throw new BusinessException(ErrorCode.EVIDENCE_TAX_INVOICE_NOT_FOUND);
        } catch (FeignException e) {
            throw new BusinessException(ErrorCode.TAX_INVOICE_REFERENCE_UNAVAILABLE);
        }
        if (response == null || !response.success() || response.data() == null
                || !taxInvoiceId.equals(response.data().id())) {
            throw new BusinessException(ErrorCode.TAX_INVOICE_REFERENCE_UNAVAILABLE);
        }
        TaxInvoiceReference invoice = response.data();
        if (!invoice.isPurchase()) {
            throw new BusinessException(ErrorCode.EVIDENCE_TAX_INVOICE_NOT_PURCHASE);
        }
        if (siteId == null || !siteId.equals(invoice.siteId())) {
            throw new BusinessException(ErrorCode.EVIDENCE_TAX_INVOICE_SITE_MISMATCH);
        }
        return invoice;
    }

    /** 현장의 매입 세금계산서 목록. 응답에 다른 현장·매출 계산서가 섞이면 제외한다. */
    public List<TaxInvoiceReference> findPurchaseInvoices(Long siteId) {
        TaxInvoiceReferenceListResponse response;
        try {
            response = client.findBySite(siteId, TaxInvoiceReference.PURCHASE_TYPE);
        } catch (FeignException e) {
            throw new BusinessException(ErrorCode.TAX_INVOICE_REFERENCE_UNAVAILABLE);
        }
        if (response == null || !response.success() || response.data() == null) {
            throw new BusinessException(ErrorCode.TAX_INVOICE_REFERENCE_UNAVAILABLE);
        }
        return response.data().stream()
                .filter(invoice -> invoice != null && invoice.id() != null)
                .filter(TaxInvoiceReference::isPurchase)
                .filter(invoice -> siteId.equals(invoice.siteId()))
                .toList();
    }
}
