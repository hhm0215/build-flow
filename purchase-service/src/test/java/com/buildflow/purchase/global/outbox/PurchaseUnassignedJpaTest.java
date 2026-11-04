package com.buildflow.purchase.global.outbox;

import com.buildflow.purchase.domain.purchase.dto.PurchaseCreateRequest;
import com.buildflow.purchase.domain.purchase.dto.PurchaseResponse;
import com.buildflow.purchase.domain.purchase.dto.PurchaseSiteAssignRequest;
import com.buildflow.purchase.domain.purchase.dto.PurchaseUpdateRequest;
import com.buildflow.purchase.domain.purchase.repository.PurchaseRepository;
import com.buildflow.purchase.domain.purchase.service.PurchaseProfitSourceService;
import com.buildflow.purchase.domain.purchase.service.PurchaseService;
import com.buildflow.purchase.domain.purchase.service.SiteReferenceValidator;
import com.buildflow.purchase.global.config.JpaAuditingConfig;
import com.buildflow.purchase.global.exception.BusinessException;
import com.buildflow.purchase.global.exception.ErrorCode;
import com.buildflow.purchase.global.kafka.KafkaProducerService;
import com.fasterxml.jackson.databind.JsonNode;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 현장 미배정 매입(2026-11-01): 미배정 동안 손익 outbox 없음, 배정 시 purchase.registered 1건. */
@DataJpaTest
@ActiveProfiles("test")
@Import({PurchaseService.class, KafkaProducerService.class, PurchaseProfitSourceService.class,
        JpaAuditingConfig.class, PurchaseUnassignedJpaTest.JsonConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PurchaseUnassignedJpaTest {

    @TestConfiguration
    static class JsonConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    @Autowired private PurchaseService purchaseService;
    @Autowired private PurchaseProfitSourceService profitSourceService;
    @MockBean private SiteReferenceValidator siteReferenceValidator;
    @Autowired private PurchaseRepository purchaseRepository;
    @Autowired private OutboxEventRepository outboxRepository;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        purchaseRepository.deleteAll();
    }

    @Test
    void unassignedCreateUpdateDeleteSkipSiteLookupAndProfitEvents() {
        PurchaseResponse created = purchaseService.create(request(null));
        assertNull(created.getSiteId());

        purchaseService.update(created.getId(), updateRequest());
        assertEquals(0, purchaseService.findById(created.getId()).getTotalAmount()
                .compareTo(new BigDecimal("6000.00")));
        purchaseService.delete(created.getId());

        assertEquals(0, purchaseRepository.count());
        assertEquals(0, outboxRepository.count());
        verify(siteReferenceValidator, never()).requireExistingSite(any());
        verify(siteReferenceValidator, never()).recheckBeforeCommit(any());
    }

    @Test
    void assigningPublishesSingleRegisteredEventWithIncreasedRevision() throws Exception {
        Long id = purchaseService.create(request(null)).getId();
        purchaseService.update(id, updateRequest());
        assertEquals(0, profitSourceService.snapshot(7L).getPurchases().size());

        PurchaseResponse assigned = purchaseService.assignSite(id, assign(7L));

        assertEquals(7L, assigned.getSiteId());
        verify(siteReferenceValidator).requireExistingSite(7L);
        verify(siteReferenceValidator).recheckBeforeCommit(7L);
        assertEquals(1, outboxRepository.count());
        OutboxEvent event = outboxRepository.findAll().get(0);
        assertEquals("purchase.registered", event.getTopic());
        assertEquals(String.valueOf(id), event.getRecordKey());
        JsonNode payload = objectMapper.readTree(event.getPayloadJson()).get("payload");
        assertEquals(7L, payload.get("siteId").asLong());
        assertEquals(3L, payload.get("revision").asLong());
        assertEquals(0, new BigDecimal(payload.get("totalAmount").asText())
                .compareTo(new BigDecimal("6000.00")));

        var source = profitSourceService.snapshot(7L);
        assertEquals(1, source.getPurchases().size());
        assertEquals(3L, source.getPurchases().get(0).getRevision());
        assertEquals(1, source.getProfitEvents().size());

        // 배정 후에는 기존 수정 이벤트 흐름으로 돌아간다
        purchaseService.update(id, updateRequest());
        assertEquals(2, outboxRepository.count());
    }

    @Test
    void sameSiteReassignIsIdempotentAndOtherSiteIsRejected() {
        Long id = purchaseService.create(request(null)).getId();
        purchaseService.assignSite(id, assign(7L));

        purchaseService.assignSite(id, assign(7L));
        BusinessException moved = assertThrows(BusinessException.class,
                () -> purchaseService.assignSite(id, assign(8L)));

        assertEquals(ErrorCode.PURCHASE_SITE_ALREADY_ASSIGNED, moved.getErrorCode());
        assertEquals(7L, purchaseRepository.findById(id).orElseThrow().getSiteId());
        assertEquals(1, outboxRepository.count());
    }

    @Test
    void archivedOrMissingSiteLeavesPurchaseUnassignedWithoutEvent() {
        Long id = purchaseService.create(request(null)).getId();
        doThrow(new BusinessException(ErrorCode.SITE_ARCHIVED))
                .when(siteReferenceValidator).requireExistingSite(9L);
        doThrow(new BusinessException(ErrorCode.INVALID_SITE_REFERENCE))
                .when(siteReferenceValidator).requireExistingSite(10L);
        doThrow(new BusinessException(ErrorCode.SITE_ARCHIVED))
                .when(siteReferenceValidator).recheckBeforeCommit(11L);

        assertEquals(ErrorCode.SITE_ARCHIVED, assertThrows(BusinessException.class,
                () -> purchaseService.assignSite(id, assign(9L))).getErrorCode());
        assertEquals(ErrorCode.INVALID_SITE_REFERENCE, assertThrows(BusinessException.class,
                () -> purchaseService.assignSite(id, assign(10L))).getErrorCode());
        assertEquals(ErrorCode.SITE_ARCHIVED, assertThrows(BusinessException.class,
                () -> purchaseService.assignSite(id, assign(11L))).getErrorCode());

        var unchanged = purchaseRepository.findById(id).orElseThrow();
        assertNull(unchanged.getSiteId());
        assertEquals(1L, unchanged.getEventRevision());
        assertEquals(0, outboxRepository.count());
    }

    @Test
    void listFiltersUnassignedAndRejectsCombinedFilter() {
        purchaseService.create(request(null));
        purchaseService.create(request(7L));

        assertEquals(2, purchaseService.findAll(null, false).size());
        assertEquals(1, purchaseService.findAll(7L, false).size());
        var unassigned = purchaseService.findAll(null, true);
        assertEquals(1, unassigned.size());
        assertNull(unassigned.get(0).getSiteId());
        assertEquals(ErrorCode.INVALID_PURCHASE_FILTER, assertThrows(BusinessException.class,
                () -> purchaseService.findAll(7L, true)).getErrorCode());
    }

    @Test
    void missingPurchaseAssignIs404() {
        assertEquals(ErrorCode.PURCHASE_NOT_FOUND, assertThrows(BusinessException.class,
                () -> purchaseService.assignSite(999L, assign(7L))).getErrorCode());
    }

    private PurchaseCreateRequest request(Long siteId) {
        PurchaseCreateRequest request = new PurchaseCreateRequest();
        ReflectionTestUtils.setField(request, "siteId", siteId);
        ReflectionTestUtils.setField(request, "itemName", "자재");
        ReflectionTestUtils.setField(request, "quantity", 2);
        ReflectionTestUtils.setField(request, "unitPrice", new BigDecimal("1500.00"));
        return request;
    }

    private PurchaseUpdateRequest updateRequest() {
        PurchaseUpdateRequest request = new PurchaseUpdateRequest();
        ReflectionTestUtils.setField(request, "itemName", "수정 자재");
        ReflectionTestUtils.setField(request, "quantity", 3);
        ReflectionTestUtils.setField(request, "unitPrice", new BigDecimal("2000.00"));
        return request;
    }

    private PurchaseSiteAssignRequest assign(Long siteId) {
        PurchaseSiteAssignRequest request = new PurchaseSiteAssignRequest();
        ReflectionTestUtils.setField(request, "siteId", siteId);
        return request;
    }
}
