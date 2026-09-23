package com.buildflow.purchase.global.outbox;

import com.buildflow.purchase.domain.purchase.dto.PurchaseCreateRequest;
import com.buildflow.purchase.domain.purchase.dto.PurchaseUpdateRequest;
import com.buildflow.purchase.domain.purchase.repository.PurchaseRepository;
import com.buildflow.purchase.domain.purchase.service.PurchaseService;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@DataJpaTest
@ActiveProfiles("test")
@Import({PurchaseService.class, KafkaProducerService.class, OutboxClaimService.class,
        JpaAuditingConfig.class, PurchaseOutboxJpaTest.JsonConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PurchaseOutboxJpaTest {

    @TestConfiguration
    static class JsonConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    @Autowired private PurchaseService purchaseService;
    @Autowired private PurchaseRepository purchaseRepository;
    @SpyBean private OutboxEventRepository outboxRepository;
    @Autowired private OutboxClaimService claimService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        purchaseRepository.deleteAll();
    }

    @Test
    void purchaseAndOriginalEventEnvelopeAreCommittedTogether() throws Exception {
        var created = purchaseService.create(request());

        assertEquals(1, purchaseRepository.count());
        assertEquals(1, outboxRepository.count());
        OutboxEvent event = outboxRepository.findAll().get(0);
        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals("purchase.registered", event.getTopic());
        assertEquals(String.valueOf(created.getId()), event.getRecordKey());
        JsonNode json = objectMapper.readTree(event.getPayloadJson());
        assertEquals(event.getEventId(), json.get("eventId").asText());
        assertEquals("PURCHASE_REGISTERED", json.get("eventType").asText());
        assertEquals(created.getId(), json.get("payload").get("purchaseId").asLong());
        assertEquals(1L, json.get("payload").get("revision").asLong());
        assertEquals(0, new BigDecimal(json.get("payload").get("totalAmount").asText())
                .compareTo(created.getTotalAmount()));
    }

    @Test
    void outboxWriteFailureRollsBackPurchase() {
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(outboxRepository).save(any(OutboxEvent.class));

        assertThrows(IllegalStateException.class, () -> purchaseService.create(request()));

        assertEquals(0, purchaseRepository.count());
        assertEquals(0, outboxRepository.count());
    }

    @Test
    void updateAndDeleteKeepOriginalEventTypesAndAmounts() throws Exception {
        Long id = purchaseService.create(request()).getId();

        purchaseService.update(id, updateRequest());
        purchaseService.delete(id);

        assertEquals(0, purchaseRepository.count());
        assertEquals(3, outboxRepository.count());
        OutboxEvent updated = outboxRepository.findAll().stream()
                .filter(event -> event.getTopic().equals("purchase.updated")).findFirst().orElseThrow();
        JsonNode updateJson = objectMapper.readTree(updated.getPayloadJson());
        assertEquals("PURCHASE_UPDATED", updateJson.get("eventType").asText());
        assertEquals(2L, updateJson.get("payload").get("revision").asLong());
        assertEquals(0, new BigDecimal(updateJson.get("payload").get("oldTotalAmount").asText())
                .compareTo(new BigDecimal("3000.00")));
        assertEquals(0, new BigDecimal(updateJson.get("payload").get("newTotalAmount").asText())
                .compareTo(new BigDecimal("6000.00")));
        OutboxEvent deleted = outboxRepository.findAll().stream()
                .filter(event -> event.getTopic().equals("purchase.deleted")).findFirst().orElseThrow();
        JsonNode deleteJson = objectMapper.readTree(deleted.getPayloadJson());
        assertEquals("PURCHASE_DELETED", deleteJson.get("eventType").asText());
        assertEquals(3L, deleteJson.get("payload").get("revision").asLong());
        assertEquals(0, new BigDecimal(deleteJson.get("payload").get("totalAmount").asText())
                .compareTo(new BigDecimal("6000.00")));
    }

    @Test
    void updateOutboxFailureRollsBackAmountChange() {
        Long id = purchaseService.create(request()).getId();
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(outboxRepository).save(any(OutboxEvent.class));

        assertThrows(IllegalStateException.class, () -> purchaseService.update(id, updateRequest()));

        assertEquals(0, purchaseRepository.findById(id).orElseThrow().getTotalAmount()
                .compareTo(new BigDecimal("3000.00")));
        assertEquals(1L, purchaseRepository.findById(id).orElseThrow().getEventRevision());
        assertEquals(1, outboxRepository.count());
    }

    @Test
    void deleteOutboxFailureRollsBackPurchaseRemoval() {
        Long id = purchaseService.create(request()).getId();
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(outboxRepository).save(any(OutboxEvent.class));

        assertThrows(IllegalStateException.class, () -> purchaseService.delete(id));

        assertTrue(purchaseRepository.existsById(id));
        assertEquals(1L, purchaseRepository.findById(id).orElseThrow().getEventRevision());
        assertEquals(1, outboxRepository.count());
    }

    @Test
    void invalidCreateAmountDoesNotWritePurchaseOrOutbox() {
        PurchaseCreateRequest invalid = request();
        ReflectionTestUtils.setField(invalid, "quantity", 1001);
        ReflectionTestUtils.setField(invalid, "unitPrice", new BigDecimal("9999999999.99"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> purchaseService.create(invalid));

        assertEquals(ErrorCode.INVALID_PURCHASE_AMOUNT, exception.getErrorCode());
        assertEquals(0, purchaseRepository.count());
        assertEquals(0, outboxRepository.count());
    }

    @Test
    void invalidUpdateAmountLeavesPurchaseAndOutboxUnchanged() {
        Long id = purchaseService.create(request()).getId();
        PurchaseUpdateRequest invalid = updateRequest();
        ReflectionTestUtils.setField(invalid, "quantity", 1001);
        ReflectionTestUtils.setField(invalid, "unitPrice", new BigDecimal("9999999999.99"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> purchaseService.update(id, invalid));

        assertEquals(ErrorCode.INVALID_PURCHASE_AMOUNT, exception.getErrorCode());
        var unchanged = purchaseRepository.findById(id).orElseThrow();
        assertEquals(0, unchanged.getTotalAmount().compareTo(new BigDecimal("3000.00")));
        assertEquals(1L, unchanged.getEventRevision());
        assertEquals(1, outboxRepository.count());
    }

    @Test
    void maximumUnitPriceWithThousandQuantityFitsDatabaseTotalRange() {
        PurchaseCreateRequest boundary = request();
        ReflectionTestUtils.setField(boundary, "quantity", 1000);
        ReflectionTestUtils.setField(boundary, "unitPrice", new BigDecimal("9999999999.99"));

        var created = purchaseService.create(boundary);

        assertEquals(0, created.getTotalAmount().compareTo(new BigDecimal("9999999999990.00")));
        assertEquals(1, purchaseRepository.count());
        assertEquals(1, outboxRepository.count());
    }

    @Test
    void revisionsAreMonotonicPerPurchaseAndStartAtOne() throws Exception {
        Long firstId = purchaseService.create(request()).getId();
        Long secondId = purchaseService.create(request()).getId();

        purchaseService.update(firstId, updateRequest());
        purchaseService.update(firstId, updateRequest());
        purchaseService.delete(firstId);

        assertEquals(5, outboxRepository.count());
        assertEquals(1L, revisionFor(secondId, "purchase.registered"));
        assertEquals(1L, revisionsFor(firstId, "purchase.registered")[0]);
        assertEquals(2L, revisionsFor(firstId, "purchase.updated")[0]);
        assertEquals(3L, revisionsFor(firstId, "purchase.updated")[1]);
        assertEquals(4L, revisionFor(firstId, "purchase.deleted"));
    }

    @Test
    void claimAndTokenGuardProtectSentAndFailureTransitions() {
        String eventId = UUID.randomUUID().toString();
        outboxRepository.saveAndFlush(new OutboxEvent(eventId, "purchase.registered", "7", "{\"eventId\":\"" + eventId + "\"}"));

        ClaimedOutboxEvent claimed = claimService.claimNext().orElseThrow();
        assertEquals(eventId, claimed.eventId());
        assertEquals(1, claimed.attempts());
        assertTrue(claimService.claimNext().isEmpty());
        assertFalse(claimService.markSent(eventId, "stale-token"));
        assertFalse(claimService.releaseForRetry(eventId, "stale-token", 1, "stale"));
        assertEquals(OutboxStatus.CLAIMED, outboxRepository.findById(eventId).orElseThrow().getStatus());
        assertTrue(claimService.markSent(eventId, claimed.claimToken()));
        assertEquals(OutboxStatus.SENT, outboxRepository.findById(eventId).orElseThrow().getStatus());
        assertTrue(claimService.claimNext().isEmpty());
    }

    @Test
    void expiredLeaseReclaimsOriginalPayloadWithNewToken() {
        String eventId = UUID.randomUUID().toString();
        String originalJson = "{\"eventId\":\"" + eventId + "\"}";
        outboxRepository.saveAndFlush(new OutboxEvent(eventId, "purchase.registered", "7", originalJson));
        ClaimedOutboxEvent first = claimService.claimNext().orElseThrow();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            OutboxEvent locked = outboxRepository.findByIdForUpdate(eventId).orElseThrow();
            ReflectionTestUtils.setField(locked, "leaseUntil", LocalDateTime.now().minusSeconds(1));
        });

        ClaimedOutboxEvent second = claimService.claimNext().orElseThrow();
        assertEquals(2, second.attempts());
        assertEquals(originalJson, second.payloadJson());
        assertEquals(first.eventId(), second.eventId());
        assertFalse(first.claimToken().equals(second.claimToken()));
        assertFalse(claimService.markSent(eventId, first.claimToken()));
        assertTrue(claimService.releaseForRetry(eventId, second.claimToken(), 2, "broker down"));
        OutboxEvent pending = outboxRepository.findById(eventId).orElseThrow();
        assertEquals(OutboxStatus.PENDING, pending.getStatus());
        assertTrue(pending.getNextAttemptAt().isAfter(LocalDateTime.now()));
        assertTrue(pending.getNextAttemptAt().isBefore(LocalDateTime.now().plusSeconds(4)));
        assertTrue(claimService.claimNext().isEmpty());
    }

    @Test
    void concurrentDispatchersClaimSingleRowOnce() throws Exception {
        String eventId = UUID.randomUUID().toString();
        outboxRepository.saveAndFlush(new OutboxEvent(eventId, "purchase.registered", "7", "{}"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                ready.countDown();
                start.await();
                return claimService.claimNext();
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int claimed = 0;
            for (var future : futures) {
                if (future.get(10, TimeUnit.SECONDS).isPresent()) claimed++;
            }
            assertEquals(1, claimed);
            assertEquals(1, outboxRepository.findById(eventId).orElseThrow().getAttempts());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private PurchaseCreateRequest request() {
        PurchaseCreateRequest request = new PurchaseCreateRequest();
        ReflectionTestUtils.setField(request, "siteId", 7L);
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

    private long revisionFor(Long purchaseId, String topic) throws Exception {
        return revisionsFor(purchaseId, topic)[0];
    }

    private long[] revisionsFor(Long purchaseId, String topic) throws Exception {
        return outboxRepository.findAll().stream()
                .filter(event -> event.getTopic().equals(topic))
                .filter(event -> event.getRecordKey().equals(String.valueOf(purchaseId)))
                .mapToLong(event -> {
                    try {
                        return objectMapper.readTree(event.getPayloadJson())
                                .get("payload").get("revision").asLong();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .sorted()
                .toArray();
    }
}
