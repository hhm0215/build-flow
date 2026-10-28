package com.buildflow.site.infra.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 손익 재계산(운영 절차) 전용: 현장의 현재 매입 금액·revision 원천 조회. */
@FeignClient(name = "purchase-service", contextId = "sitePurchaseProfitSourceClient")
public interface PurchaseProfitSourceClient {

    @GetMapping("/api/v1/purchases/profit-source")
    ProfitSourceResponse<PurchaseProfitSource> snapshot(@RequestParam("siteId") Long siteId);
}
