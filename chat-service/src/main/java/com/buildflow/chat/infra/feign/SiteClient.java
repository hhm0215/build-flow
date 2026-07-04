package com.buildflow.chat.infra.feign;

import com.buildflow.chat.infra.feign.dto.DashboardSummaryDto;
import com.buildflow.chat.infra.feign.dto.FeignApiResponse;
import com.buildflow.chat.infra.feign.dto.ProfitDto;
import com.buildflow.chat.infra.feign.dto.SiteDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

@FeignClient(name = "site-service")
public interface SiteClient {

    @GetMapping("/api/v1/sites")
    FeignApiResponse<List<SiteDto>> listSites();

    @GetMapping("/api/v1/sites/{id}/profit")
    FeignApiResponse<ProfitDto> getProfit(@PathVariable("id") Long id);

    @GetMapping("/api/v1/dashboard/summary")
    FeignApiResponse<DashboardSummaryDto> getSummary();
}
