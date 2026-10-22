package com.buildflow.notification.domain.warranty.service;

import com.buildflow.notification.domain.warranty.dto.WarrantyAuditResponse;
import com.buildflow.notification.domain.warranty.dto.WarrantyCreateRequest;
import com.buildflow.notification.domain.warranty.dto.WarrantyUpdateRequest;
import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.entity.WarrantyAuditAction;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import com.buildflow.notification.domain.warranty.repository.WarrantyAuditEventRepository;
import com.buildflow.notification.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({DefectWarrantyService.class, WarrantyOcrResultService.class, WarrantyAuditRecorder.class, WarrantyFileRetentionService.class, JpaAuditingConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WarrantyAuditJpaTest {

    @Autowired private DefectWarrantyService service;
    @Autowired private WarrantyOcrResultService results;
    @Autowired private DefectWarrantyRepository warranties;
    @Autowired private WarrantyAuditEventRepository audits;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockBean private WarrantyOcrService ocrService;
    @MockBean private SiteReferenceValidator siteReferenceValidator;

    @BeforeEach
    void clean() {
        audits.deleteAll();
        warranties.deleteAll();
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void actingAs(String header) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (header != null) request.addHeader("X-User-Id", header);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private WarrantyCreateRequest createRequest() {
        WarrantyCreateRequest request = new WarrantyCreateRequest();
        ReflectionTestUtils.setField(request, "siteId", 7L);
        ReflectionTestUtils.setField(request, "insuranceCompany", "서울보증보험");
        ReflectionTestUtils.setField(request, "startDate", LocalDate.of(2026, 10, 17));
        ReflectionTestUtils.setField(request, "endDate", LocalDate.of(2028, 10, 16));
        return request;
    }

    private WarrantyUpdateRequest updateRequest(String insurer) {
        WarrantyUpdateRequest request = new WarrantyUpdateRequest();
        ReflectionTestUtils.setField(request, "insuranceCompany", insurer);
        ReflectionTestUtils.setField(request, "startDate", LocalDate.of(2026, 10, 17));
        ReflectionTestUtils.setField(request, "endDate", LocalDate.of(2028, 10, 31));
        return request;
    }

    @Test
    void 등록_수정_삭제가_시간순_이력으로_남고_삭제_뒤에도_조회된다() {
        actingAs("metal");
        Long id = service.create(createRequest()).getId();
        service.update(id, updateRequest("삼성화재"));
        service.delete(id);

        List<WarrantyAuditResponse> history = service.findHistory(id);

        assertThat(history).extracting(WarrantyAuditResponse::getAction).containsExactly(
                WarrantyAuditAction.CREATED, WarrantyAuditAction.MANUAL_UPDATED, WarrantyAuditAction.DELETED);
        assertThat(history).extracting(WarrantyAuditResponse::getActor).containsOnly("metal");
        assertThat(history.get(0).getInsuranceCompany()).isEqualTo("서울보증보험");
        assertThat(history.get(1).getInsuranceCompany()).isEqualTo("삼성화재");
        assertThat(history.get(1).getEndDate()).isEqualTo(LocalDate.of(2028, 10, 31));
        assertThat(history.get(1).getExpiryConfirmedAt()).isNotNull();
        assertThat(warranties.existsById(id)).isFalse();
    }

    @Test
    void OCR_반영과_실패는_요청_밖이라_system으로_기록된다() {
        DefectWarranty pending = warranties.save(DefectWarranty.createPending(7L, "/app/uploads/warranties/qa.pdf"));
        DefectWarranty failing = warranties.save(DefectWarranty.createPending(7L, "/app/uploads/warranties/qa2.pdf"));

        results.apply(pending.getId(), "서울보증보험", null, LocalDate.of(2026, 10, 17), LocalDate.of(2028, 10, 16));
        results.markFailed(failing.getId());

        assertThat(service.findHistory(pending.getId())).singleElement().satisfies(event -> {
            assertThat(event.getAction()).isEqualTo(WarrantyAuditAction.OCR_APPLIED);
            assertThat(event.getActor()).isEqualTo("system");
            assertThat(event.getInsuranceCompany()).isEqualTo("서울보증보험");
        });
        assertThat(service.findHistory(failing.getId())).extracting(WarrantyAuditResponse::getAction)
                .containsExactly(WarrantyAuditAction.OCR_FAILED);
    }

    @Test
    void 롤백된_변경은_이력도_남기지_않는다() {
        actingAs("metal");
        Long id = service.create(createRequest()).getId();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.update(id, updateRequest("삼성화재"));
            status.setRollbackOnly();
        });

        assertThat(service.findHistory(id)).extracting(WarrantyAuditResponse::getAction)
                .containsExactly(WarrantyAuditAction.CREATED);
        assertThat(warranties.findById(id).orElseThrow().getInsuranceCompany()).isEqualTo("서울보증보험");
    }

    @Test
    void 형식이_다른_사용자_헤더는_기록하지_않고_unknown으로_둔다() {
        actingAs("metal'; DROP TABLE x;--");
        Long id = service.create(createRequest()).getId();
        actingAs(null);
        service.update(id, updateRequest("삼성화재"));

        assertThat(service.findHistory(id)).extracting(WarrantyAuditResponse::getActor)
                .containsExactly("unknown", "unknown");
    }
}
