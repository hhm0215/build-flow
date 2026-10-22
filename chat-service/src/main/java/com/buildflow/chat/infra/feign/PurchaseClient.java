package com.buildflow.chat.infra.feign;

import com.buildflow.chat.infra.feign.dto.FeignApiResponse;
import com.buildflow.chat.infra.feign.dto.PurchaseSummaryDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "purchase-service")
public interface PurchaseClient {

    @GetMapping("/api/v1/purchases")
    FeignApiResponse<List<PurchaseSummaryDto>> listBySite(@RequestParam("siteId") Long siteId);
}
