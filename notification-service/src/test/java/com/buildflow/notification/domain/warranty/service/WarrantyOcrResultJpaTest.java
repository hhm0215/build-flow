package com.buildflow.notification.domain.warranty.service;

import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.entity.OcrStatus;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import com.buildflow.notification.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({WarrantyOcrResultService.class, WarrantyOcrRecoveryService.class, JpaAuditingConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WarrantyOcrResultJpaTest {

    @Autowired private WarrantyOcrResultService results;
    @Autowired private WarrantyOcrRecoveryService recovery;
    @Autowired private DefectWarrantyRepository repository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void OCR_결과는_PENDING_행에만_커밋된다() {
        DefectWarranty pending = repository.save(DefectWarranty.createPending(1L, "synthetic.pdf"));
        LocalDate end = LocalDate.of(2027, 1, 1);
        assertThat(results.apply(pending.getId(), "보험사", "POL-1", null, end)).isTrue();
        DefectWarranty saved = repository.findById(pending.getId()).orElseThrow();
        assertThat(saved.getOcrStatus()).isEqualTo(OcrStatus.SUCCESS);
        assertThat(saved.getInsuranceCompany()).isEqualTo("보험사");
        assertThat(saved.getEndDate()).isEqualTo(end);
        assertThat(results.apply(pending.getId(), "다른 값", null, null, null)).isFalse();
        assertThat(repository.findById(pending.getId()).orElseThrow().getInsuranceCompany()).isEqualTo("보험사");
    }

    @Test
    void 삭제된_보증서는_OCR_콜백으로_다시_생성되지_않는다() {
        DefectWarranty pending = repository.save(DefectWarranty.createPending(1L, "synthetic.pdf"));
        Long id = pending.getId();
        repository.deleteById(id);
        assertThat(results.apply(id, "보험사", null, null, null)).isFalse();
        results.markFailed(id);
        assertThat(repository.existsById(id)).isFalse();
    }

    @Test
    void 실패한_OCR은_FAILED로_전환하되_완료된_행을_덮어쓰지_않는다() {
        DefectWarranty pending = repository.save(DefectWarranty.createPending(1L, "synthetic.pdf"));
        results.markFailed(pending.getId());
        assertThat(repository.findById(pending.getId()).orElseThrow().getOcrStatus()).isEqualTo(OcrStatus.FAILED);
        results.markFailed(pending.getId());
        assertThat(repository.findById(pending.getId()).orElseThrow().getOcrStatus()).isEqualTo(OcrStatus.FAILED);
    }

    @Test
    void 재시작으로_유실된_오래된_PENDING만_FAILED로_복구한다() {
        DefectWarranty stale = repository.save(DefectWarranty.createPending(1L, "stale.pdf"));
        DefectWarranty fresh = repository.save(DefectWarranty.createPending(1L, "fresh.pdf"));
        jdbc.update("UPDATE defect_warranties SET created_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusHours(1)), stale.getId());
        recovery.failStalePending();
        assertThat(repository.findById(stale.getId()).orElseThrow().getOcrStatus()).isEqualTo(OcrStatus.FAILED);
        assertThat(repository.findById(fresh.getId()).orElseThrow().getOcrStatus()).isEqualTo(OcrStatus.PENDING);
    }
}
