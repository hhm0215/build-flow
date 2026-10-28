package com.buildflow.notification.domain.warranty.service;

import com.buildflow.notification.domain.warranty.dto.WarrantyCreateRequest;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import com.buildflow.notification.domain.warranty.repository.WarrantyAuditEventRepository;
import com.buildflow.notification.global.exception.BusinessException;
import com.buildflow.notification.global.exception.ErrorCode;
import com.buildflow.notification.global.config.JpaAuditingConfig;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@DataJpaTest
@ActiveProfiles("test")
@Import({DefectWarrantyService.class, WarrantyAuditRecorder.class, WarrantyFileRetentionService.class, JpaAuditingConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WarrantyUploadTransactionJpaTest {

    @Autowired private DefectWarrantyService service;
    @Autowired private DefectWarrantyRepository repository;
    @Autowired private WarrantyAuditEventRepository auditRepository;
    @MockBean private WarrantyOcrService ocr;
    @MockBean private SiteReferenceValidator siteReferenceValidator;
    @TempDir Path tempDir;

    @Test
    void OCR_예약_시점에_PENDING_행은_이미_별도_트랜잭션에서_조회된다() throws Exception {
        ReflectionTestUtils.setField((Object) AopTestUtils.getTargetObject(service), "uploadDir", tempDir.toString());
        doAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(repository.findById(id)).isPresent();
            return null;
        }).when(ocr).processAsync(any(), any());

        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(out);
            pdf = out.toByteArray();
        }
        var response = service.createFromOcr(1L, new MockMultipartFile("file", "synthetic.pdf", "application/pdf", pdf));
        assertThat(repository.findById(response.getId())).isPresent();
    }

    @Test
    void 업로드_저장_후_현장이_보관되면_행과_이력과_파일이_남지_않고_OCR도_예약되지_않는다() throws Exception {
        ReflectionTestUtils.setField((Object) AopTestUtils.getTargetObject(service), "uploadDir", tempDir.toString());
        long warrantiesBefore = repository.count();
        long auditsBefore = auditRepository.count();
        doThrow(new BusinessException(ErrorCode.SITE_ARCHIVED)).when(siteReferenceValidator).recheckBeforeCommit(1L);

        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(out);
            pdf = out.toByteArray();
        }
        assertThatThrownBy(() -> service.createFromOcr(1L,
                new MockMultipartFile("file", "synthetic.pdf", "application/pdf", pdf)))
                .isInstanceOf(BusinessException.class);

        assertThat(repository.count()).isEqualTo(warrantiesBefore);
        assertThat(auditRepository.count()).isEqualTo(auditsBefore);
        try (var files = java.nio.file.Files.list(tempDir)) {
            assertThat(files.toList()).isEmpty();
        }
        verifyNoInteractions(ocr);
    }

    @Test
    void 직접_등록_저장_후_현장이_보관되면_행과_이력이_롤백된다() {
        long warrantiesBefore = repository.count();
        long auditsBefore = auditRepository.count();
        doThrow(new BusinessException(ErrorCode.SITE_ARCHIVED)).when(siteReferenceValidator).recheckBeforeCommit(1L);
        WarrantyCreateRequest request = org.springframework.beans.BeanUtils.instantiateClass(WarrantyCreateRequest.class);
        ReflectionTestUtils.setField(request, "siteId", 1L);
        ReflectionTestUtils.setField(request, "insuranceCompany", "합성 보험사");
        ReflectionTestUtils.setField(request, "startDate", java.time.LocalDate.of(2026, 1, 1));
        ReflectionTestUtils.setField(request, "endDate", java.time.LocalDate.of(2027, 1, 1));

        assertThatThrownBy(() -> service.create(request)).isInstanceOf(BusinessException.class);

        assertThat(repository.count()).isEqualTo(warrantiesBefore);
        assertThat(auditRepository.count()).isEqualTo(auditsBefore);
    }
}
