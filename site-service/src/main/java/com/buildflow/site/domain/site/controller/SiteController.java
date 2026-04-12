package com.buildflow.site.domain.site.controller;

import com.buildflow.site.domain.site.dto.SiteCreateRequest;
import com.buildflow.site.domain.site.dto.SiteResponse;
import com.buildflow.site.domain.site.dto.SiteStatusUpdateRequest;
import com.buildflow.site.domain.site.dto.SiteUpdateRequest;
import com.buildflow.site.domain.site.entity.SiteStatus;
import com.buildflow.site.domain.site.service.SiteService;
import com.buildflow.site.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sites")
@RequiredArgsConstructor
public class SiteController {

    private final SiteService siteService;

    @PostMapping
    public ResponseEntity<ApiResponse<SiteResponse>> create(
            @Valid @RequestBody SiteCreateRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(siteService.create(request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<SiteResponse>>> findAll(
            @RequestParam(required = false) SiteStatus status) {
        return ResponseEntity.ok(ApiResponse.success(siteService.findAll(status)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SiteResponse>> findById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(siteService.findById(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<SiteResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody SiteUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(siteService.update(id, request)));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<SiteResponse>> changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody SiteStatusUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(siteService.changeStatus(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        siteService.delete(id);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
