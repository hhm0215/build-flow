package com.buildflow.purchase.domain.purchase.controller;

import com.buildflow.purchase.domain.purchase.dto.PurchaseCreateRequest;
import com.buildflow.purchase.domain.purchase.dto.PurchaseResponse;
import com.buildflow.purchase.domain.purchase.dto.PurchaseUpdateRequest;
import com.buildflow.purchase.domain.purchase.service.PurchaseService;
import com.buildflow.purchase.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/purchases")
@RequiredArgsConstructor
public class PurchaseController {

    private final PurchaseService purchaseService;

    @PostMapping
    public ResponseEntity<ApiResponse<PurchaseResponse>> create(
            @Valid @RequestBody PurchaseCreateRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(purchaseService.create(request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<PurchaseResponse>>> findAll(
            @RequestParam(required = false) Long siteId) {
        return ResponseEntity.ok(ApiResponse.success(purchaseService.findAll(siteId)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PurchaseResponse>> findById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(purchaseService.findById(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<PurchaseResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody PurchaseUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(purchaseService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        purchaseService.delete(id);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
