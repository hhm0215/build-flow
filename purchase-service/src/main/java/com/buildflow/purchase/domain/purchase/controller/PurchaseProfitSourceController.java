package com.buildflow.purchase.domain.purchase.controller;

import com.buildflow.purchase.domain.purchase.dto.PurchaseProfitSourceResponse;
import com.buildflow.purchase.domain.purchase.service.PurchaseProfitSourceService;
import com.buildflow.purchase.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** site-service 손익 재계산이 호출하는 원천 스냅샷(운영 절차용, 프론트 미노출). */
@RestController
@RequestMapping("/api/v1/purchases")
@RequiredArgsConstructor
public class PurchaseProfitSourceController {

    private final PurchaseProfitSourceService purchaseProfitSourceService;

    @GetMapping("/profit-source")
    public ResponseEntity<ApiResponse<PurchaseProfitSourceResponse>> profitSource(
            @RequestParam Long siteId) {
        return ResponseEntity.ok(ApiResponse.success(purchaseProfitSourceService.snapshot(siteId)));
    }
}
