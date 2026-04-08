package com.buildflow.estimate.domain.estimate.controller;

import com.buildflow.estimate.domain.estimate.dto.EstimateCreateRequest;
import com.buildflow.estimate.domain.estimate.dto.EstimateResponse;
import com.buildflow.estimate.domain.estimate.dto.EstimateUpdateRequest;
import com.buildflow.estimate.domain.estimate.service.EstimateService;
import com.buildflow.estimate.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/estimates")
@RequiredArgsConstructor
public class EstimateController {

    private final EstimateService estimateService;

    @PostMapping
    public ResponseEntity<ApiResponse<EstimateResponse>> create(
            @Valid @RequestBody EstimateCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(estimateService.create(request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<EstimateResponse>>> findAll(
            @RequestParam(required = false) Long siteId) {
        List<EstimateResponse> result = siteId != null
                ? estimateService.findBySiteId(siteId)
                : estimateService.findAll();
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<EstimateResponse>> findById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(estimateService.findById(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<EstimateResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody EstimateUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(estimateService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        estimateService.delete(id);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PatchMapping("/{id}/confirm")
    public ResponseEntity<ApiResponse<EstimateResponse>> confirm(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(estimateService.confirm(id)));
    }
}
