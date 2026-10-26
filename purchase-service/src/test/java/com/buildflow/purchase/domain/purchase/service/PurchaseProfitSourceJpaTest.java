package com.buildflow.purchase.domain.purchase.service;

import com.buildflow.purchase.domain.purchase.dto.PurchaseCreateRequest;
import com.buildflow.purchase.domain.purchase.dto.PurchaseProfitSourceResponse;
import com.buildflow.purchase.domain.purchase.dto.PurchaseUpdateRequest;
import com.buildflow.purchase.domain.purchase.repository.PurchaseRepository;
import com.buildflow.purchase.global.config.JpaAuditingConfig;
import com.buildflow.purchase.global.exception.BusinessException;
import com.buildflow.purchase.global.exception.ErrorCode;
import com.buildflow.purchase.global.kafka.KafkaProducerService;
import com.buildflow.purchase.global.outbox.OutboxEvent;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 현재 금액·revision과 삭제된 매입의 지연 이벤트를 포함하는 손익 원천 스냅샷. */
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
        assertEquals(5, source.getProfitEvents().size());
        Set<String> otherSiteEvents = outboxRepository.findAll().stream()
                .filter(event -> event.getPayloadJson().contains("\"siteId\":8"))
                .map(OutboxEvent::getEventId)
                .collect(Collectors.toSet());
        assertTrue(source.getProfitEvents().stream()
                .noneMatch(event -> otherSiteEvents.contains(event.getEventId())));
    }

    @Test
    void emptySiteReturnsZeroAndInvalidSiteIsRejected() {
        PurchaseProfitSourceResponse empty = sourceService.snapshot(99L);
        assertEquals(0, BigDecimal.ZERO.compareTo(empty.getTotalAmount()));
        assertTrue(empty.getPurchases().isEmpty());
        assertTrue(empty.getProfitEvents().isEmpty());

        BusinessException invalid = assertThrows(BusinessException.class, () -> sourceService.snapshot(-1L));
        assertEquals(ErrorCode.INVALID_SITE_REFERENCE, invalid.getErrorCode());
    }

    @Test
    void deletedPurchaseStillReturnsRegisteredUpdatedAndDeletedEventIds() {
        Long deleted = purchaseService.create(request(7L, 1, "100.00")).getId();
        purchaseService.update(deleted, updateRequest(1, "200.00"));
        purchaseService.delete(deleted);
        Set<String> eventIds = outboxRepository.findAll().stream()
                .map(OutboxEvent::getEventId).collect(Collectors.toSet());

        PurchaseProfitSourceResponse source = sourceService.snapshot(7L);

        assertTrue(source.getPurchases().isEmpty());
        assertEquals(0, source.getTotalAmount().compareTo(BigDecimal.ZERO));
        assertEquals(eventIds, source.getProfitEvents().stream()
                .map(PurchaseProfitSourceResponse.ProfitEvent::getEventId).collect(Collectors.toSet()));
        assertEquals(Set.of("PURCHASE_REGISTERED", "PURCHASE_UPDATED", "PURCHASE_DELETED"),
                source.getProfitEvents().stream()
                        .map(PurchaseProfitSourceResponse.ProfitEvent::getEventType).collect(Collectors.toSet()));
        assertTrue(sourceService.snapshot(8L).getProfitEvents().isEmpty());
    }

    @Test
    void snapshotIncludesPendingClaimedAndSentEvents() {
        OutboxEvent pending = event("purchase.registered", "PURCHASE_REGISTERED");
        OutboxEvent claimed = event("purchase.updated", "PURCHASE_UPDATED");
        claimed.claim(LocalDateTime.now().plusSeconds(1), LocalDateTime.now().plusMinutes(1));
        OutboxEvent sent = event("purchase.deleted", "PURCHASE_DELETED");
        sent.markSent(LocalDateTime.now());
        outboxRepository.saveAllAndFlush(List.of(pending, claimed, sent));

        Set<String> ids = sourceService.snapshot(7L).getProfitEvents().stream()
                .map(PurchaseProfitSourceResponse.ProfitEvent::getEventId).collect(Collectors.toSet());

        assertEquals(Set.of(pending.getEventId(), claimed.getEventId(), sent.getEventId()), ids);
    }

    @Test
    void malformedOrUnattributablePayloadFailsClosedWithGenericServiceUnavailable() {
        String id = UUID.randomUUID().toString();
        String valid = payload(id, "PURCHASE_REGISTERED", "7", "11");
        List<String> malformed = List.of(
                "private-payload-marker",
                "null",
                "[]",
                "{}",
                payload(id, "PURCHASE_REGISTERED", "null", "11"),
                payload(id, "PURCHASE_REGISTERED", "0", "11"),
                payload(id, "PURCHASE_REGISTERED", "-1", "11"),
                payload(id, "PURCHASE_REGISTERED", "7.1", "11"),
                payload(id, "PURCHASE_REGISTERED", "\"7\"", "11"),
                payload(id, "PURCHASE_REGISTERED", "9223372036854775808", "11"),
                payload(id, "PURCHASE_UPDATED", "7", "11"),
                payload(UUID.randomUUID().toString(), "PURCHASE_REGISTERED", "7", "11"),
                payload(id, "PURCHASE_REGISTERED", "7", "12"),
                valid.replace("\"siteId\":7,", ""));

        for (String json : malformed) {
            outboxRepository.saveAndFlush(new OutboxEvent(id, "purchase.registered", "11", json));
            BusinessException failure = assertThrows(BusinessException.class, () -> sourceService.snapshot(7L));
            assertEquals(ErrorCode.PROFIT_SOURCE_UNAVAILABLE, failure.getErrorCode());
            assertEquals(503, failure.getErrorCode().getStatus().value());
            assertEquals("손익 원천 정보를 확인할 수 없습니다.", failure.getErrorCode().getMessage());
            outboxRepository.deleteAll();
        }
    }

    @Test
    void malformedEventIdAndUnattributableOtherSiteEventAlsoBlockSnapshot() {
        outboxRepository.saveAndFlush(new OutboxEvent("not-a-uuid", "purchase.registered", "11",
                payload("not-a-uuid", "PURCHASE_REGISTERED", "7", "11")));
        assertEquals(ErrorCode.PROFIT_SOURCE_UNAVAILABLE, assertThrows(BusinessException.class,
                () -> sourceService.snapshot(7L)).getErrorCode());
        outboxRepository.deleteAll();

        String id = UUID.randomUUID().toString();
        // 요청 현장 밖의 이벤트도 소속을 신뢰할 수 없다면 전체 재계산을 중단한다.
        outboxRepository.saveAndFlush(new OutboxEvent(id, "purchase.registered", "11",
                payload(id, "PURCHASE_DELETED", "8", "11")));
        assertEquals(ErrorCode.PROFIT_SOURCE_UNAVAILABLE, assertThrows(BusinessException.class,
                () -> sourceService.snapshot(7L)).getErrorCode());
    }

    private OutboxEvent event(String topic, String type) {
        String id = UUID.randomUUID().toString();
        return new OutboxEvent(id, topic, "11", payload(id, type, "7", "11"));
    }

    private String payload(String eventId, String type, String siteIdJson, String purchaseIdJson) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + type
                + "\",\"payload\":{\"siteId\":" + siteIdJson + ",\"purchaseId\":" + purchaseIdJson + "}}";
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
