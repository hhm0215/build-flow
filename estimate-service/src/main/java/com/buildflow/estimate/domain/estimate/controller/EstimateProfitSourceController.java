package com.buildflow.estimate.domain.estimate.controller;

import com.buildflow.estimate.domain.estimate.dto.EstimateProfitSourceResponse;
import com.buildflow.estimate.domain.estimate.service.EstimateProfitSourceService;
import com.buildflow.estimate.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** site-service 손익 재계산이 호출하는 원천 스냅샷(운영 절차용, 프론트 미노출). */
@RestController
@RequestMapping("/api/v1/estimates")
@RequiredArgsConstructor
public class EstimateProfitSourceController {

    private final EstimateProfitSourceService estimateProfitSourceService;

    @GetMapping("/profit-source")
    public ResponseEntity<ApiResponse<EstimateProfitSourceResponse>> profitSource(
            @RequestParam Long siteId) {
        return ResponseEntity.ok(ApiResponse.success(estimateProfitSourceService.snapshot(siteId)));
    }
}
