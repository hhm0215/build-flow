package com.buildflow.site.domain.profit.controller;

import com.buildflow.site.domain.profit.dto.ProfitResponse;
import com.buildflow.site.domain.profit.service.ProfitService;
import com.buildflow.site.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sites")
@RequiredArgsConstructor
public class ProfitController {

    private final ProfitService profitService;

    @GetMapping("/{id}/profit")
    public ResponseEntity<ApiResponse<ProfitResponse>> getProfit(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(profitService.getProfit(id)));
    }
}
