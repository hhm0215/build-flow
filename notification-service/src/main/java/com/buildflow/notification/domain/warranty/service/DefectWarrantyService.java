package com.buildflow.notification.domain.warranty.service;

import com.buildflow.notification.domain.warranty.dto.WarrantyCreateRequest;
import com.buildflow.notification.domain.warranty.dto.WarrantyResponse;
import com.buildflow.notification.domain.warranty.dto.WarrantyUpdateRequest;
import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import com.buildflow.notification.global.exception.BusinessException;
import com.buildflow.notification.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefectWarrantyService {

    private final DefectWarrantyRepository warrantyRepository;
    private final WarrantyOcrService ocrService;

    @Value("${app.upload-dir:./uploads/warranties}")
    private String uploadDir;

    @Transactional
    public WarrantyResponse create(WarrantyCreateRequest request) {
        DefectWarranty warranty = DefectWarranty.builder()
                .siteId(request.getSiteId())
                .insuranceCompany(request.getInsuranceCompany())
                .policyNumber(request.getPolicyNumber())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .coverageAmount(request.getCoverageAmount())
                .memo(request.getMemo())
                .build();

        return WarrantyResponse.from(warrantyRepository.save(warranty));
    }

    public List<WarrantyResponse> findAll(Long siteId) {
        List<DefectWarranty> warranties = (siteId != null)
                ? warrantyRepository.findBySiteIdOrderByEndDateAsc(siteId)
                : warrantyRepository.findAllByOrderByEndDateAsc();

        return warranties.stream()
                .map(WarrantyResponse::from)
                .toList();
    }

    public WarrantyResponse findById(Long id) {
        return WarrantyResponse.from(getWarranty(id));
    }

    @Transactional
    public WarrantyResponse update(Long id, WarrantyUpdateRequest request) {
        DefectWarranty warranty = getWarranty(id);
        warranty.update(
                request.getInsuranceCompany(),
                request.getStartDate(),
                request.getEndDate(),
                request.getPolicyNumber(),
                request.getCoverageAmount(),
                request.getMemo()
        );
        return WarrantyResponse.from(warranty);
    }

    @Transactional
    public void delete(Long id) {
        DefectWarranty warranty = getWarranty(id);
        String filePath = warranty.getFilePath();
        warrantyRepository.delete(warranty);
        // 트랜잭션 커밋 후에만 파일 삭제 — rollback 시 파일은 남고 다음 cleanup job 대상이 됨.
        // (커밋 전 삭제 시 rollback이 일어나면 DB row는 남고 파일은 사라진 dangling 상태가 됨)
        if (filePath != null && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteFileQuietly(filePath);
                }
            });
        }
    }

    private void deleteFileQuietly(String filePath) {
        try {
            Path p = Paths.get(filePath);
            if (Files.deleteIfExists(p)) {
                log.info("업로드 파일 삭제: {}", p);
            }
        } catch (IOException e) {
            // 파일이 이미 사라졌거나 권한 문제 — DB는 이미 정리됐으므로 로그만 남김 (orphan 파일은 별도 cleanup job 검토)
            log.warn("업로드 파일 삭제 실패 (orphan 가능): path={} cause={}", filePath, e.getMessage());
        }
    }

    public List<WarrantyResponse> findExpiringSoon(int days) {
        LocalDate now = LocalDate.now();
        LocalDate threshold = now.plusDays(days);
        return warrantyRepository.findExpiringSoon(now, threshold).stream()
                .map(WarrantyResponse::from)
                .toList();
    }

    /**
     * PDF 업로드 → 파일 저장 → PENDING 레코드 즉시 생성 → 비동기 OCR 트리거.
     * 컨트롤러는 즉시 PENDING WarrantyResponse를 반환 (HTTP 202).
     *
     * <p>NOTE(@Transactional 의도적 미부착): processAsync가 outer tx 커밋 전에 실행되면
     * 비동기 스레드의 findById가 PENDING row를 못 봐 영구 PENDING 사고가 발생함.
     * `savePending`만 Spring Data JPA가 자체 짧은 tx로 commit한 후 `processAsync` 호출.
     */
    public WarrantyResponse createFromOcr(Long siteId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.WARRANTY_FILE_EMPTY);
        }
        Path stored = storeFile(file);
        DefectWarranty pending = warrantyRepository.save(
                DefectWarranty.createPending(siteId, stored.toString()));
        ocrService.processAsync(pending.getId(), stored);
        log.info("warranty PENDING 생성 + OCR 비동기 트리거 id={} siteId={} path={}",
                pending.getId(), siteId, stored);
        return WarrantyResponse.from(pending);
    }

    private Path storeFile(MultipartFile file) {
        try {
            Path dir = Paths.get(uploadDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.pdf";
            String ext = original.contains(".") ? original.substring(original.lastIndexOf('.')) : ".pdf";
            Path target = dir.resolve(UUID.randomUUID() + ext);
            file.transferTo(target);
            return target;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.WARRANTY_FILE_STORAGE_FAILED);
        }
    }

    private DefectWarranty getWarranty(Long id) {
        return warrantyRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.WARRANTY_NOT_FOUND));
    }
}
