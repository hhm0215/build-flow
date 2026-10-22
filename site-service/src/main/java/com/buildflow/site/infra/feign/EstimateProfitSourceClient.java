package com.buildflow.site.infra.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 손익 재계산(운영 절차) 전용: 확정 견적 합계와 반영된 손익 eventId 원천 조회. */
@FeignClient(name = "estimate-service", contextId = "siteEstimateProfitSourceClient")
public interface EstimateProfitSourceClient {

    @GetMapping("/api/v1/estimates/profit-source")
    ProfitSourceResponse<EstimateProfitSource> snapshot(@RequestParam("siteId") Long siteId);
}
