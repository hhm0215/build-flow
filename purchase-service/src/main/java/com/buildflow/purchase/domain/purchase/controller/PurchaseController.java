package com.buildflow.purchase.domain.purchase.controller;

import com.buildflow.purchase.domain.purchase.dto.EvidenceLinkCountResponse;
import com.buildflow.purchase.domain.purchase.dto.PurchaseCreateRequest;
import com.buildflow.purchase.domain.purchase.dto.PurchaseEvidenceLinkRequest;
import com.buildflow.purchase.domain.purchase.dto.PurchaseEvidenceSummaryResponse;
import com.buildflow.purchase.domain.purchase.dto.PurchaseResponse;
import com.buildflow.purchase.domain.purchase.dto.PurchaseUpdateRequest;
import com.buildflow.purchase.domain.purchase.service.PurchaseEvidenceService;
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
    private final PurchaseEvidenceService purchaseEvidenceService;

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

    /** 현장별 매입 항목 ↔ 매입 세금계산서 증빙 대조 요약. */
    @GetMapping("/evidence-summary")
    public ResponseEntity<ApiResponse<PurchaseEvidenceSummaryResponse>> evidenceSummary(
            @RequestParam Long siteId) {
        return ResponseEntity.ok(ApiResponse.success(purchaseEvidenceService.summarize(siteId)));
    }

    /** tax-service가 매입 계산서 삭제·유형 변경 전에 호출하는 연결 건수 조회. */
    @GetMapping("/evidence-links")
    public ResponseEntity<ApiResponse<EvidenceLinkCountResponse>> evidenceLinks(
            @RequestParam Long taxInvoiceId) {
        return ResponseEntity.ok(ApiResponse.success(purchaseEvidenceService.countLinks(taxInvoiceId)));
    }

    @PutMapping("/{id}/evidence")
    public ResponseEntity<ApiResponse<PurchaseResponse>> linkEvidence(
            @PathVariable Long id,
            @Valid @RequestBody PurchaseEvidenceLinkRequest request) {
        return ResponseEntity.ok(ApiResponse.success(purchaseEvidenceService.link(id, request)));
    }

    @DeleteMapping("/{id}/evidence")
    public ResponseEntity<ApiResponse<PurchaseResponse>> unlinkEvidence(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(purchaseEvidenceService.unlink(id)));
    }
}
