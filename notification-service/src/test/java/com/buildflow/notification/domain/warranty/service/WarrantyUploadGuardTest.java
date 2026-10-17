package com.buildflow.notification.domain.warranty.service;

import com.buildflow.notification.domain.warranty.repository.WarrantyAuditEventRepository;

import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.entity.OcrStatus;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import com.buildflow.notification.global.exception.BusinessException;
import com.buildflow.notification.global.exception.ErrorCode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WarrantyUploadGuardTest {

    @TempDir Path tempDir;

    private DefectWarrantyRepository repository = mock(DefectWarrantyRepository.class);
    private WarrantyOcrService ocr = mock(WarrantyOcrService.class);
    private SiteReferenceValidator siteReferenceValidator = mock(SiteReferenceValidator.class);

    private DefectWarrantyService service() {
        DefectWarrantyService service = new DefectWarrantyService(repository, ocr, mock(WarrantyAuditEventRepository.class), siteReferenceValidator, mock(WarrantyAuditRecorder.class));
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());
        return service;
    }

    private byte[] pdf(int pages) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) document.addPage(new PDPage());
            document.save(out);
            return out.toByteArray();
        }
    }

    private MockMultipartFile upload(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/pdf", bytes);
    }

    private List<Path> files() throws IOException {
        try (var paths = Files.list(tempDir)) {
            return paths.toList();
        }
    }

    @Test
    void 정상_PDF는_원래_확장자와_무관하게_pdf로_저장하고_OCR을_예약한다() throws Exception {
        when(repository.save(any())).thenAnswer(invocation -> {
            DefectWarranty warranty = invocation.getArgument(0);
            ReflectionTestUtils.setField(warranty, "id", 42L);
            return warranty;
        });
        var result = service().createFromOcr(1L, upload("untrusted.exe", pdf(1)));
        assertThat(result.getOcrStatus()).isEqualTo(OcrStatus.PENDING);
        assertThat(result.getId()).isEqualTo(42L);
        assertThat(files()).hasSize(1)
                .allSatisfy(path -> assertThat(path.getFileName().toString()).endsWith(".pdf").doesNotContain("untrusted"));
        verify(ocr).processAsync(eq(42L), any(Path.class));
    }

    @Test
    void 없는_현장_업로드는_파일과_DB와_OCR을_남기지_않는다() throws Exception {
        doThrow(new BusinessException(ErrorCode.INVALID_SITE_REFERENCE))
                .when(siteReferenceValidator).requireExistingSite(99L);

        assertThatThrownBy(() -> service().createFromOcr(99L, upload("x.pdf", pdf(1))))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_SITE_REFERENCE));
        assertThat(files()).isEmpty();
        verifyNoInteractions(repository, ocr);
    }

    @Test
    void 현장_확인_장애는_업로드를_저장_전에_실패_닫힘으로_거부한다() throws Exception {
        doThrow(new BusinessException(ErrorCode.SITE_REFERENCE_UNAVAILABLE))
                .when(siteReferenceValidator).requireExistingSite(1L);

        assertThatThrownBy(() -> service().createFromOcr(1L, upload("x.pdf", pdf(1))))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.SITE_REFERENCE_UNAVAILABLE));
        assertThat(files()).isEmpty();
        verifyNoInteractions(repository, ocr);
    }

    @Test
    void 비PDF와_손상_PDF는_파일과_DB를_남기지_않는다() throws Exception {
        DefectWarrantyService service = service();
        assertThatThrownBy(() -> service.createFromOcr(1L, upload("x.pdf", "not pdf".getBytes())))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode()).isEqualTo(ErrorCode.WARRANTY_INVALID_PDF));
        assertThatThrownBy(() -> service.createFromOcr(1L, upload("x.pdf", "%PDF-broken".getBytes())))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode()).isEqualTo(ErrorCode.WARRANTY_INVALID_PDF));
        assertThat(files()).isEmpty();
        verifyNoInteractions(repository, ocr);
    }

    @Test
    void 페이지_초과는_저장과_OCR을_거부한다() throws Exception {
        assertThatThrownBy(() -> service().createFromOcr(1L, upload("x.pdf", pdf(11))))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode()).isEqualTo(ErrorCode.WARRANTY_INVALID_PDF));
        assertThat(files()).isEmpty();
        verifyNoInteractions(repository, ocr);
    }

    @Test
    void 거대_페이지는_업로드_단계에서_거부한다() throws Exception {
        byte[] huge;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage(new PDRectangle(5000, 5000)));
            document.save(out);
            huge = out.toByteArray();
        }
        assertThatThrownBy(() -> service().createFromOcr(1L, upload("x.pdf", huge)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode()).isEqualTo(ErrorCode.WARRANTY_INVALID_PDF));
        assertThat(files()).isEmpty();
        verifyNoInteractions(repository, ocr);
    }

    @Test
    void 암호화_PDF는_업로드_단계에서_거부한다() throws Exception {
        byte[] encrypted;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-password", "user-password", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(out);
            encrypted = out.toByteArray();
        }
        assertThatThrownBy(() -> service().createFromOcr(1L, upload("x.pdf", encrypted)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode()).isEqualTo(ErrorCode.WARRANTY_INVALID_PDF));
        assertThat(files()).isEmpty();
        verifyNoInteractions(repository, ocr);
    }

    @Test
    void 크기_한도는_저장_전에_거부한다() throws Exception {
        byte[] small = "%PDF-".getBytes();
        MockMultipartFile oversized = new MockMultipartFile("file", "x.pdf", "application/pdf", small) {
            @Override public long getSize() { return DefectWarrantyService.MAX_PDF_BYTES + 1; }
        };
        assertThatThrownBy(() -> service().createFromOcr(1L, oversized))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode()).isEqualTo(ErrorCode.WARRANTY_FILE_TOO_LARGE));
        assertThat(files()).isEmpty();
        verifyNoInteractions(repository, ocr);
    }

    @Test
    void 잘못된_크기_메타데이터도_실제_복사량으로_다시_거부한다() throws Exception {
        byte[] bytes = new byte[(int) DefectWarrantyService.MAX_PDF_BYTES + 1];
        MockMultipartFile dishonest = new MockMultipartFile("file", "x.pdf", "application/pdf", bytes) {
            @Override public long getSize() { return 1; }
        };
        assertThatThrownBy(() -> service().createFromOcr(1L, dishonest))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode()).isEqualTo(ErrorCode.WARRANTY_FILE_TOO_LARGE));
        assertThat(files()).isEmpty();
        verifyNoInteractions(repository, ocr);
    }

    @Test
    void DB_저장_실패는_업로드_파일을_정리한다() throws Exception {
        when(repository.save(any())).thenThrow(new IllegalStateException("DB unavailable"));
        assertThatThrownBy(() -> service().createFromOcr(1L, upload("x.pdf", pdf(1))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(files()).isEmpty();
        verifyNoInteractions(ocr);
    }

    @Test
    void OCR_대기열이_포화되면_영구_PENDING을_남기지_않는다() throws Exception {
        when(repository.save(any())).thenAnswer(invocation -> {
            DefectWarranty warranty = invocation.getArgument(0);
            ReflectionTestUtils.setField(warranty, "id", 42L);
            return warranty;
        });
        doThrow(new TaskRejectedException("full")).when(ocr).processAsync(eq(42L), any(Path.class));
        var result = service().createFromOcr(1L, upload("x.pdf", pdf(1)));
        assertThat(result.getOcrStatus()).isEqualTo(OcrStatus.FAILED);
        verify(repository, times(2)).save(any());
    }

    @Test
    void 업로드_오케스트레이션은_클래스_readOnly_트랜잭션을_중단한다() throws Exception {
        var method = DefectWarrantyService.class.getMethod("createFromOcr", Long.class, org.springframework.web.multipart.MultipartFile.class);
        var attribute = new AnnotationTransactionAttributeSource().getTransactionAttribute(method, DefectWarrantyService.class);
        assertThat(attribute).isNotNull();
        assertThat(attribute.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        assertThat(attribute.isReadOnly()).isFalse();
    }
}
