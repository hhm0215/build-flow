package com.buildflow.tax.domain.taxinvoice.controller;

import com.buildflow.tax.domain.taxinvoice.dto.*;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoiceType;
import com.buildflow.tax.domain.taxinvoice.service.TaxInvoiceService;
import com.buildflow.tax.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/taxes")
@RequiredArgsConstructor
public class TaxInvoiceController {

    private final TaxInvoiceService taxInvoiceService;

    @PostMapping
    public ResponseEntity<ApiResponse<TaxInvoiceResponse>> create(
            @Valid @RequestBody TaxInvoiceCreateRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(taxInvoiceService.create(request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TaxInvoiceResponse>>> findAll(
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) TaxInvoiceType type) {
        return ResponseEntity.ok(ApiResponse.success(taxInvoiceService.findAll(siteId, type)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TaxInvoiceResponse>> findById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(taxInvoiceService.findById(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TaxInvoiceResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody TaxInvoiceUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(taxInvoiceService.update(id, request)));
    }

    @PatchMapping("/{id}/confirm-payment")
    public ResponseEntity<ApiResponse<TaxInvoiceResponse>> confirmPayment(
            @PathVariable Long id,
            @RequestBody PaymentConfirmRequest request) {
        return ResponseEntity.ok(ApiResponse.success(taxInvoiceService.confirmPayment(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        taxInvoiceService.delete(id);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @GetMapping("/outstanding")
    public ResponseEntity<ApiResponse<OutstandingResponse>> getOutstanding(
            @RequestParam Long siteId) {
        return ResponseEntity.ok(ApiResponse.success(taxInvoiceService.getOutstanding(siteId)));
    }
}
