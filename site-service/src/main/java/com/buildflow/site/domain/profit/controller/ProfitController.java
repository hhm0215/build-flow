package com.buildflow.site.domain.profit.controller;

import com.buildflow.site.domain.profit.dto.ProfitReconcileResponse;
import com.buildflow.site.domain.profit.dto.ProfitResponse;
import com.buildflow.site.domain.profit.service.ProfitReconcileService;
import com.buildflow.site.domain.profit.service.ProfitService;
import com.buildflow.site.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sites")
@RequiredArgsConstructor
public class ProfitController {

    private final ProfitService profitService;
    private final ProfitReconcileService profitReconcileService;

    @GetMapping("/{id}/profit")
    public ResponseEntity<ApiResponse<ProfitResponse>> getProfit(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(profitService.getProfit(id)));
    }

    /**
     * 운영 절차용 손익 재계산. 기본은 dry-run(차이만 보고), apply=true일 때만 원천 값으로 반영한다.
     * 원천 조회 실패는 503으로 실패-닫힘.
     */
    @PostMapping("/{id}/profit/reconcile")
    public ResponseEntity<ApiResponse<ProfitReconcileResponse>> reconcile(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean apply) {
        return ResponseEntity.ok(ApiResponse.success(profitReconcileService.reconcile(id, apply)));
    }
}
