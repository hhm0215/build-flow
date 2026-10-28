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
import org.springframework.transaction.support.TransactionTemplate;
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
    private final TransactionTemplate transactionTemplate;

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
        warrantyRepository.flush();
        // 첫 검증 후 저장 사이에 현장이 보관됐으면 보증·이력을 함께 롤백한다
        siteReferenceValidator.recheckBeforeCommit(saved.getSiteId());
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
        warrantyRepository.flush();
        // 첫 검사 직후 현장이 보관됐으면 보정·이력을 커밋 전에 롤백한다 (ADR-021 보관 현장 문서 잠금)
        siteReferenceValidator.recheckNotArchivedBeforeCommit(warranty.getSiteId());
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
        warrantyRepository.flush();
        // 파일은 커밋 후 보존 기한 정리 작업만 지우므로, 여기서 롤백돼도 원본 PDF·보존 기록 정합이 깨지지 않는다
        siteReferenceValidator.recheckNotArchivedBeforeCommit(warranty.getSiteId());
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
     * 기존 readOnly 트랜잭션을 중단하고, PENDING 행·업로드 이력 저장과 커밋 직전 현장 재확인을
     * 한 트랜잭션으로 커밋한 뒤 OCR을 예약한다. 재확인 실패(보관·삭제·장애)면 행·이력이 롤백되고
     * 이미 디스크에 쓴 파일도 지운 뒤 예외를 전파하므로 OCR은 예약되지 않는다.
     * 콜백은 끝났는데 커밋 단계에서 예외가 나면(연결 끊김 등) 커밋 여부가 불확실하므로, 행 존재를 다시 조회해
     * 커밋된 행이 있으면 파일을 보존한다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public WarrantyResponse createFromOcr(Long siteId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.WARRANTY_FILE_EMPTY);
        }
        siteReferenceValidator.requireExistingSite(siteId);
        Path stored = storeFile(file);
        DefectWarranty pending;
        // 콜백이 끝까지 실행됐는지 기록해, 이후 예외가 커밋 단계(결과 불확실)에서 났는지 구분한다
        boolean[] callbackCompleted = {false};
        try {
            pending = transactionTemplate.execute(status -> {
                DefectWarranty saved = warrantyRepository.save(DefectWarranty.createPending(siteId, stored.toString()));
                auditRecorder.record(WarrantyAuditAction.UPLOADED, saved);
                warrantyRepository.flush();
                siteReferenceValidator.recheckBeforeCommit(siteId);
                callbackCompleted[0] = true;
                return saved;
            });
        } catch (RuntimeException e) {
            if (!callbackCompleted[0] || !pendingRowMayBeCommitted(stored, e)) {
                deleteFileQuietly(stored.toString());
            }
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

    /**
     * 커밋 단계 예외(콜백 완료 후)는 DB가 실제로 커밋됐는지 알 수 없다. 새 읽기 트랜잭션으로 저장 경로를
     * 가리키는 행을 확인해, 행이 있으면 원본을 보존한다(true). 행이 없으면 false(기존처럼 삭제).
     * 확인 자체가 실패하면 원본 보존(true)을 택한다: 커밋된 PENDING 행은 30분 복구 작업이 FAILED로 바꿀 뿐
     * 행·원본 참조는 남아 원본 다운로드·수동 보정에 쓰이므로, 파일을 지우면 복구 불가능한 깨진 참조가 되지만
     * 남기면 최악이 디스크의 고아 파일 하나다(원본 보존 우선 — docs/PRODUCT_VISION.md).
     */
    private boolean pendingRowMayBeCommitted(Path stored, RuntimeException commitFailure) {
        TransactionTemplate readOnly = new TransactionTemplate(transactionTemplate.getTransactionManager());
        readOnly.setReadOnly(true);
        try {
            Boolean exists = readOnly.execute(status -> warrantyRepository.existsByFilePath(stored.toString()));
            boolean committed = Boolean.TRUE.equals(exists);
            log.warn("보증보험 업로드 커밋 결과 불확실 causeType={} rowCommitted={}",
                    commitFailure.getClass().getSimpleName(), committed);
            return committed;
        } catch (RuntimeException lookupFailure) {
            log.warn("보증보험 업로드 커밋 결과 확인 실패 — 원본 파일 보존 causeType={} lookupCauseType={}",
                    commitFailure.getClass().getSimpleName(), lookupFailure.getClass().getSimpleName());
            return true;
        }
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
