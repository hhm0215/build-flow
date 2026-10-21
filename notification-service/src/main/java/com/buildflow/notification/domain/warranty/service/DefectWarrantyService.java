package com.buildflow.notification.domain.warranty.service;

import com.buildflow.notification.domain.warranty.WarrantyDatePolicy;
import com.buildflow.notification.domain.warranty.dto.WarrantyAuditResponse;
import com.buildflow.notification.domain.warranty.dto.WarrantyCreateRequest;
import com.buildflow.notification.domain.warranty.dto.WarrantyResponse;
import com.buildflow.notification.domain.warranty.dto.WarrantyUpdateRequest;
import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.entity.OcrStatus;
import com.buildflow.notification.domain.warranty.entity.WarrantyAuditAction;
import com.buildflow.notification.domain.warranty.repository.WarrantyAuditEventRepository;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import com.buildflow.notification.global.exception.BusinessException;
import com.buildflow.notification.global.exception.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefectWarrantyService {

    static final long MAX_PDF_BYTES = 20L * 1024 * 1024;
    static final int MAX_PDF_PAGES = 10;

    private final DefectWarrantyRepository warrantyRepository;
    private final WarrantyOcrService ocrService;
    private final WarrantyAuditEventRepository auditRepository;
    private final SiteReferenceValidator siteReferenceValidator;
    private final WarrantyAuditRecorder auditRecorder;
    private final WarrantyFileRetentionService fileRetention;

    @Value("${app.upload-dir:./uploads/warranties}")
    private String uploadDir;

    @Transactional
    public WarrantyResponse create(WarrantyCreateRequest request) {
        validatePeriod(request.getStartDate(), request.getEndDate());
        validateAmount(request.getCoverageAmount());
        siteReferenceValidator.requireExistingSite(request.getSiteId());
        DefectWarranty warranty = DefectWarranty.builder()
                .siteId(request.getSiteId())
                .insuranceCompany(request.getInsuranceCompany())
                .policyNumber(request.getPolicyNumber())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .coverageAmount(request.getCoverageAmount())
                .memo(request.getMemo())
                .build();

        DefectWarranty saved = warrantyRepository.save(warranty);
        auditRecorder.record(WarrantyAuditAction.CREATED, saved);
        return WarrantyResponse.from(saved);
    }

    public List<WarrantyAuditResponse> findHistory(Long id) {
        return auditRepository.findByWarrantyIdOrderByOccurredAtAscIdAsc(id).stream()
                .map(WarrantyAuditResponse::from)
                .toList();
    }

    public List<WarrantyResponse> findAll(Long siteId) {
        List<DefectWarranty> warranties = (siteId != null)
                ? warrantyRepository.findBySiteIdOrderByEndDateAsc(siteId)
                : warrantyRepository.findAllByOrderByEndDateAsc();
        LocalDate today = WarrantyDatePolicy.today();

        return warranties.stream()
                .map(warranty -> WarrantyResponse.from(warranty, today))
                .toList();
    }

    public WarrantyResponse findById(Long id) {
        return WarrantyResponse.from(getWarranty(id));
    }

    @Transactional
    public WarrantyResponse update(Long id, WarrantyUpdateRequest request) {
        DefectWarranty warranty = getWarrantyForUpdate(id);
        siteReferenceValidator.requireSiteNotArchived(warranty.getSiteId());
        if (warranty.getOcrStatus() == OcrStatus.PENDING) {
            throw new BusinessException(ErrorCode.WARRANTY_OCR_PENDING);
        }
        validatePeriod(request.getStartDate(), request.getEndDate());
        if (request.getCoverageAmount() != null) {
            request.getCoverageAmount().ifPresent(this::validateAmount);
        }
        warranty.update(
                request.getInsuranceCompany(),
                request.getStartDate(),
                request.getEndDate(),
                request.getPolicyNumber(),
                request.getCoverageAmount(),
                request.getMemo()
        );
        auditRecorder.record(WarrantyAuditAction.MANUAL_UPDATED, warranty);
        return WarrantyResponse.from(warranty);
    }

    @Transactional
    public void delete(Long id) {
        DefectWarranty warranty = getWarrantyForUpdate(id);
        siteReferenceValidator.requireSiteNotArchived(warranty.getSiteId());
        auditRecorder.record(WarrantyAuditAction.DELETED, warranty);
        // 원본 보존: 파일은 즉시 지우지 않고 보존 기록만 같은 트랜잭션에 남긴다. 기한이 지나면 정리 작업이 폐기한다.
        fileRetention.retain(warranty);
        warrantyRepository.delete(warranty);
    }

    private void deleteFileQuietly(String filePath) {
        try {
            Path p = Paths.get(filePath);
            if (Files.deleteIfExists(p)) {
                log.info("보증보험 업로드 파일 삭제 완료");
            }
        } catch (IOException e) {
            // 파일이 이미 사라졌거나 권한 문제 — DB는 이미 정리됐으므로 로그만 남김 (orphan 파일은 별도 cleanup job 검토)
            log.warn("보증보험 업로드 파일 삭제 실패 (orphan 가능): causeType={}", e.getClass().getSimpleName());
        }
    }

    public List<WarrantyResponse> findExpiringSoon(int days) {
        LocalDate now = WarrantyDatePolicy.today();
        LocalDate threshold = now.plusDays(days);
        return warrantyRepository.findExpiringSoon(now, threshold,
                        List.of(OcrStatus.MANUAL)).stream()
                .map(warranty -> WarrantyResponse.from(warranty, now))
                .toList();
    }

    /**
     * PDF 업로드 → 파일 저장 → PENDING 레코드 즉시 생성 → 비동기 OCR 트리거.
     * 컨트롤러는 즉시 PENDING WarrantyResponse를 반환 (HTTP 202).
     *
     * 기존 readOnly 트랜잭션을 중단하고 repository.save 자체 트랜잭션이 커밋된 뒤 OCR을 예약한다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public WarrantyResponse createFromOcr(Long siteId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.WARRANTY_FILE_EMPTY);
        }
        siteReferenceValidator.requireExistingSite(siteId);
        Path stored = storeFile(file);
        DefectWarranty pending;
        try {
            pending = warrantyRepository.save(DefectWarranty.createPending(siteId, stored.toString()));
            auditRecorder.record(WarrantyAuditAction.UPLOADED, pending);
        } catch (RuntimeException e) {
            deleteFileQuietly(stored.toString());
            throw e;
        }
        try {
            ocrService.processAsync(pending.getId(), stored);
        } catch (TaskRejectedException e) {
            pending.markOcrFailed();
            warrantyRepository.save(pending);
            auditRecorder.record(WarrantyAuditAction.OCR_FAILED, pending);
            log.warn("OCR 작업 대기열 포화 warrantyId={}", pending.getId());
        }
        log.info("warranty 업로드 접수 id={} siteId={} status={}",
                pending.getId(), siteId, pending.getOcrStatus());
        return WarrantyResponse.from(pending);
    }

    private Path storeFile(MultipartFile file) {
        try {
            Path dir = Paths.get(uploadDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            if (file.getSize() > MAX_PDF_BYTES) {
                throw new BusinessException(ErrorCode.WARRANTY_FILE_TOO_LARGE);
            }
            Path target = dir.resolve(UUID.randomUUID() + ".pdf");
            try {
                try (InputStream in = file.getInputStream();
                     OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[8192];
                    long count = 0;
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        count += n;
                        if (count > MAX_PDF_BYTES) {
                            throw new BusinessException(ErrorCode.WARRANTY_FILE_TOO_LARGE);
                        }
                        out.write(buffer, 0, n);
                    }
                }
                validatePdf(target);
                return target;
            } catch (IOException | RuntimeException e) {
                deleteFileQuietly(target.toString());
                throw e;
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.WARRANTY_FILE_STORAGE_FAILED);
        }
    }

    private void validatePdf(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] header = in.readNBytes(5);
            if (header.length != 5 || header[0] != '%' || header[1] != 'P'
                    || header[2] != 'D' || header[3] != 'F' || header[4] != '-') {
                throw new BusinessException(ErrorCode.WARRANTY_INVALID_PDF);
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.WARRANTY_INVALID_PDF);
        }
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            if (document.isEncrypted() || document.getNumberOfPages() < 1
                    || document.getNumberOfPages() > MAX_PDF_PAGES) {
                throw new BusinessException(ErrorCode.WARRANTY_INVALID_PDF);
            }
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                WarrantyOcrService.validateRenderSize(document.getPage(i).getCropBox(), 300);
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.WARRANTY_INVALID_PDF);
        } catch (RuntimeException e) {
            if (e instanceof BusinessException) throw e;
            throw new BusinessException(ErrorCode.WARRANTY_INVALID_PDF);
        }
    }

    private DefectWarranty getWarranty(Long id) {
        return warrantyRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.WARRANTY_NOT_FOUND));
    }

    private DefectWarranty getWarrantyForUpdate(Long id) {
        return warrantyRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.WARRANTY_NOT_FOUND));
    }

    private void validatePeriod(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new BusinessException(ErrorCode.WARRANTY_INVALID_PERIOD);
        }
    }

    private void validateAmount(Long amount) {
        if (amount != null && amount < 0) {
            throw new BusinessException(ErrorCode.WARRANTY_INVALID_AMOUNT);
        }
    }
}
