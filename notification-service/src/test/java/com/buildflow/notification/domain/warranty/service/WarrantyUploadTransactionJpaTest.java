package com.buildflow.notification.domain.warranty.service;

import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@DataJpaTest
@ActiveProfiles("test")
@Import({DefectWarrantyService.class, WarrantyAuditRecorder.class, WarrantyFileRetentionService.class, JpaAuditingConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WarrantyUploadTransactionJpaTest {

    @Autowired private DefectWarrantyService service;
    @Autowired private DefectWarrantyRepository repository;
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
}
