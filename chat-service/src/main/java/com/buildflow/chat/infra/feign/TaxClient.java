package com.buildflow.chat.infra.feign;

import com.buildflow.chat.infra.feign.dto.FeignApiResponse;
import com.buildflow.chat.infra.feign.dto.OutstandingDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "tax-service")
public interface TaxClient {

    @GetMapping("/api/v1/taxes/outstanding")
    FeignApiResponse<OutstandingDto> getOutstanding(@RequestParam("siteId") Long siteId);
}
