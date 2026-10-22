package com.buildflow.tax.infra.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 매입 세금계산서 삭제·유형 변경 전 purchase-service의 증빙 연결 건수를 읽는다. */
@FeignClient(name = "purchase-service", contextId = "taxPurchaseEvidenceClient")
public interface PurchaseEvidenceClient {

    @GetMapping("/api/v1/purchases/evidence-links")
    PurchaseEvidenceLinkResponse countLinks(@RequestParam("taxInvoiceId") Long taxInvoiceId);
}
