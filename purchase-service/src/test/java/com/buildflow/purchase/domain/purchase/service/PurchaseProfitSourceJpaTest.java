package com.buildflow.purchase.domain.purchase.service;

import com.buildflow.purchase.domain.purchase.dto.PurchaseCreateRequest;
import com.buildflow.purchase.domain.purchase.dto.PurchaseProfitSourceResponse;
import com.buildflow.purchase.domain.purchase.dto.PurchaseUpdateRequest;
import com.buildflow.purchase.domain.purchase.repository.PurchaseRepository;
import com.buildflow.purchase.global.config.JpaAuditingConfig;
import com.buildflow.purchase.global.exception.BusinessException;
import com.buildflow.purchase.global.exception.ErrorCode;
import com.buildflow.purchase.global.kafka.KafkaProducerService;
import com.buildflow.purchase.global.outbox.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 손익 재계산 원천 스냅샷: 현재 매입 행의 금액·revision(삭제된 매입 제외). */
@DataJpaTest
@ActiveProfiles("test")
@Import({PurchaseService.class, PurchaseProfitSourceService.class, KafkaProducerService.class,
        JpaAuditingConfig.class, PurchaseProfitSourceJpaTest.JsonConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PurchaseProfitSourceJpaTest {

    @TestConfiguration
    static class JsonConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    @Autowired private PurchaseService purchaseService;
    @Autowired private PurchaseProfitSourceService sourceService;
    @Autowired private PurchaseRepository purchaseRepository;
    @Autowired private OutboxEventRepository outboxRepository;
    @MockBean private SiteReferenceValidator siteReferenceValidator;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        purchaseRepository.deleteAll();
    }

    @Test
    void returnsCurrentPurchasesWithLatestRevisionAndExcludesDeleted() {
        Long kept = purchaseService.create(request(7L, 2, "1500.00")).getId();
        Long updated = purchaseService.create(request(7L, 1, "100.00")).getId();
        purchaseService.update(updated, updateRequest(3, "2000.00"));
        Long deleted = purchaseService.create(request(7L, 1, "999.00")).getId();
        purchaseService.delete(deleted);
        purchaseService.create(request(8L, 1, "50.00"));

        PurchaseProfitSourceResponse source = sourceService.snapshot(7L);

        assertEquals(7L, source.getSiteId());
        assertEquals(0, new BigDecimal("9000.00").compareTo(source.getTotalAmount()));
        assertEquals(2, source.getPurchases().size());
        PurchaseProfitSourceResponse.Item first = source.getPurchases().get(0);
        PurchaseProfitSourceResponse.Item second = source.getPurchases().get(1);
        assertEquals(kept, first.getPurchaseId());
        assertEquals(1L, first.getRevision());
        assertEquals(0, new BigDecimal("3000.00").compareTo(first.getTotalAmount()));
        assertEquals(updated, second.getPurchaseId());
        assertEquals(2L, second.getRevision());
        assertEquals(0, new BigDecimal("6000.00").compareTo(second.getTotalAmount()));
    }

    @Test
    void emptySiteReturnsZeroAndInvalidSiteIsRejected() {
        PurchaseProfitSourceResponse empty = sourceService.snapshot(99L);
        assertEquals(0, BigDecimal.ZERO.compareTo(empty.getTotalAmount()));
        assertTrue(empty.getPurchases().isEmpty());

        BusinessException invalid = assertThrows(BusinessException.class, () -> sourceService.snapshot(-1L));
        assertEquals(ErrorCode.INVALID_SITE_REFERENCE, invalid.getErrorCode());
    }

    private PurchaseCreateRequest request(Long siteId, int quantity, String unitPrice) {
        PurchaseCreateRequest request = new PurchaseCreateRequest();
        ReflectionTestUtils.setField(request, "siteId", siteId);
        ReflectionTestUtils.setField(request, "itemName", "자재");
        ReflectionTestUtils.setField(request, "quantity", quantity);
        ReflectionTestUtils.setField(request, "unitPrice", new BigDecimal(unitPrice));
        return request;
    }

    private PurchaseUpdateRequest updateRequest(int quantity, String unitPrice) {
        PurchaseUpdateRequest request = new PurchaseUpdateRequest();
        ReflectionTestUtils.setField(request, "itemName", "수정 자재");
        ReflectionTestUtils.setField(request, "quantity", quantity);
        ReflectionTestUtils.setField(request, "unitPrice", new BigDecimal(unitPrice));
        return request;
    }
}
