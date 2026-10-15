package com.buildflow.notification.domain.warranty.controller;

import com.buildflow.notification.domain.warranty.dto.WarrantyCreateRequest;
import com.buildflow.notification.domain.warranty.dto.WarrantyResponse;
import com.buildflow.notification.domain.warranty.dto.WarrantyUpdateRequest;
import com.buildflow.notification.domain.warranty.service.DefectWarrantyService;
import com.buildflow.notification.domain.warranty.service.WarrantyOriginalFileService;
import com.buildflow.notification.domain.warranty.service.WarrantyOriginalFileService.OpenedPdf;
import com.buildflow.notification.domain.warranty.service.WarrantyFileAuthorizationService;
import com.buildflow.notification.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.InputStreamResource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;

@RestController
@RequestMapping("/api/v1/warranties")
@RequiredArgsConstructor
public class DefectWarrantyController {

    private final DefectWarrantyService warrantyService;
    private final WarrantyOriginalFileService originalFileService;
    private final WarrantyFileAuthorizationService fileAuthorizationService;

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

    @GetMapping(value = "/{id}/file", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<InputStreamResource> downloadOriginal(@PathVariable Long id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        fileAuthorizationService.requireAdminAccess(authorization);
        OpenedPdf pdf = originalFileService.open(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.size())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("warranty-" + id + ".pdf").build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(pdf.resource());
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

    /**
     * PDF 업로드 + OCR 비동기 처리.
     * 즉시 PENDING 상태 WarrantyResponse + HTTP 202 Accepted 반환.
     * 클라이언트는 GET /api/v1/warranties/{id}로 처리 결과 폴링.
     */
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<WarrantyResponse>> upload(
            @RequestParam(value = "siteId") Long siteId,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(warrantyService.createFromOcr(siteId, file)));
    }
}
