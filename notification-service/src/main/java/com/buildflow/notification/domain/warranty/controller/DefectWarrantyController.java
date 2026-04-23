package com.buildflow.notification.domain.warranty.controller;

import com.buildflow.notification.domain.warranty.dto.WarrantyCreateRequest;
import com.buildflow.notification.domain.warranty.dto.WarrantyResponse;
import com.buildflow.notification.domain.warranty.dto.WarrantyUpdateRequest;
import com.buildflow.notification.domain.warranty.service.DefectWarrantyService;
import com.buildflow.notification.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/warranties")
@RequiredArgsConstructor
public class DefectWarrantyController {

    private final DefectWarrantyService warrantyService;

    @PostMapping
    public ResponseEntity<ApiResponse<WarrantyResponse>> create(
            @Valid @RequestBody WarrantyCreateRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(warrantyService.create(request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<WarrantyResponse>>> findAll(
            @RequestParam(required = false) Long siteId) {
        return ResponseEntity.ok(ApiResponse.success(warrantyService.findAll(siteId)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<WarrantyResponse>> findById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(warrantyService.findById(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<WarrantyResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody WarrantyUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(warrantyService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        warrantyService.delete(id);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @GetMapping("/expiring")
    public ResponseEntity<ApiResponse<List<WarrantyResponse>>> findExpiringSoon(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.success(warrantyService.findExpiringSoon(days)));
    }
}
