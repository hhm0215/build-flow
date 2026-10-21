package com.buildflow.notification.domain.warranty.service;

import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.entity.WarrantyFileRetention;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import com.buildflow.notification.domain.warranty.repository.WarrantyFileRetentionRepository;
import com.buildflow.notification.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({DefectWarrantyService.class, WarrantyAuditRecorder.class, WarrantyFileRetentionService.class, JpaAuditingConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WarrantyFileRetentionJpaTest {

    @Autowired private DefectWarrantyService service;
    @Autowired private WarrantyFileRetentionService retentionService;
    @Autowired private DefectWarrantyRepository warranties;
    @Autowired private WarrantyFileRetentionRepository retentions;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockBean private WarrantyOcrService ocrService;
    @MockBean private SiteReferenceValidator siteReferenceValidator;
    @TempDir Path uploadDir;

    @BeforeEach
    void setUp() {
        retentions.deleteAll();
        warranties.deleteAll();
        ReflectionTestUtils.setField((Object) AopTestUtils.getTargetObject(retentionService), "uploadDir", uploadDir.toString());
        ReflectionTestUtils.setField((Object) AopTestUtils.getTargetObject(retentionService), "retentionDays", 365);
    }

    private Path storedPdf() throws Exception {
        Path file = uploadDir.resolve(UUID.randomUUID() + ".pdf");
        Files.writeString(file, "%PDF-synthetic");
        return file;
    }

    @Test
    void 보증을_지워도_원본은_남고_보존_기록이_생긴다() throws Exception {
        Path file = storedPdf();
        Long id = warranties.save(DefectWarranty.createPending(7L, file.toString())).getId();

        service.delete(id);

        assertThat(file).exists();
        assertThat(warranties.existsById(id)).isFalse();
        assertThat(retentions.findAll()).singleElement().satisfies(r -> {
            assertThat(r.getWarrantyId()).isEqualTo(id);
            assertThat(r.getStoredName()).isEqualTo(file.getFileName().toString());
            assertThat(r.getPurgeAfter()).isAfter(LocalDate.now().plusDays(360));
            assertThat(r.getPurgedAt()).isNull();
        });
    }

    @Test
    void 삭제가_롤백되면_보존_기록도_없다() throws Exception {
        Path file = storedPdf();
        Long id = warranties.save(DefectWarranty.createPending(7L, file.toString())).getId();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.delete(id);
            status.setRollbackOnly();
        });

        assertThat(warranties.existsById(id)).isTrue();
        assertThat(retentions.count()).isZero();
        assertThat(file).exists();
    }

    @Test
    void 기한이_지난_보존_파일만_폐기한다() throws Exception {
        Path due = storedPdf();
        Path notDue = storedPdf();
        retentions.save(WarrantyFileRetention.retain(1L, due.getFileName().toString(),
                LocalDateTime.now().minusDays(400), LocalDate.now().minusDays(1)));
        retentions.save(WarrantyFileRetention.retain(2L, notDue.getFileName().toString(),
                LocalDateTime.now(), LocalDate.now().plusDays(365)));

        assertThat(retentionService.purgeExpired()).isEqualTo(1);

        assertThat(due).doesNotExist();
        assertThat(notDue).exists();
        assertThat(retentions.findAll()).filteredOn(r -> r.getPurgedAt() != null).singleElement()
                .extracting(WarrantyFileRetention::getWarrantyId).isEqualTo(1L);
        assertThat(retentionService.purgeExpired()).isZero();
    }

    @Test
    void 이름_형식이_다르거나_폴더를_벗어나는_항목은_지우지_않는다() throws Exception {
        Path outside = Files.writeString(uploadDir.getParent().resolve("keep-" + UUID.randomUUID() + ".pdf"), "x");
        retentions.save(WarrantyFileRetention.retain(3L, "../" + outside.getFileName(),
                LocalDateTime.now().minusDays(400), LocalDate.now().minusDays(1)));

        assertThat(retentionService.purgeExpired()).isZero();
        assertThat(outside).exists();
        Files.deleteIfExists(outside);
    }
}
