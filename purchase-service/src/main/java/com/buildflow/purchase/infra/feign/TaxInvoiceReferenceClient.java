package com.buildflow.purchase.infra.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/** 증빙 연결 검증·대조 요약을 위한 tax-service 읽기 전용 조회. DB 직접 접근 대신 API로만 읽는다. */
@FeignClient(name = "tax-service", contextId = "purchaseTaxInvoiceReferenceClient")
public interface TaxInvoiceReferenceClient {

    @GetMapping("/api/v1/taxes/{id}")
    TaxInvoiceReferenceResponse findById(@PathVariable("id") Long id);

    @GetMapping("/api/v1/taxes")
    TaxInvoiceReferenceListResponse findBySite(@RequestParam("siteId") Long siteId,
                                               @RequestParam("type") String type);
}
