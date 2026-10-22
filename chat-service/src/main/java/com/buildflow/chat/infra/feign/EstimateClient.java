package com.buildflow.chat.infra.feign;

import com.buildflow.chat.infra.feign.dto.EstimateSummaryDto;
import com.buildflow.chat.infra.feign.dto.FeignApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "estimate-service")
public interface EstimateClient {

    @GetMapping("/api/v1/estimates")
    FeignApiResponse<List<EstimateSummaryDto>> listBySite(@RequestParam("siteId") Long siteId);
}
