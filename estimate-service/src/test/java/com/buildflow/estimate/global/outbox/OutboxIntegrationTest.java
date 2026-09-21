package com.buildflow.estimate.global.outbox;

import com.buildflow.estimate.domain.estimate.entity.Estimate;
import com.buildflow.estimate.domain.estimate.entity.EstimateStatus;
import com.buildflow.estimate.domain.estimate.repository.EstimateRepository;
import com.buildflow.estimate.domain.estimate.service.EstimateService;
import com.buildflow.estimate.domain.estimate.event.EstimateParsedPayload;
import com.buildflow.estimate.global.kafka.KafkaProducerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxIntegrationTest {

    private final EstimateService estimateService;
    private final EstimateRepository estimateRepository;
    private final OutboxEventRepository outboxRepository;
    private final OutboxClaimService claimService;
    private final KafkaProducerService eventProducer;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;

    OutboxIntegrationTest(EstimateService estimateService, EstimateRepository estimateRepository,
                          OutboxEventRepository outboxRepository, OutboxClaimService claimService,
                          KafkaProducerService eventProducer, ObjectMapper objectMapper, JdbcTemplate jdbcTemplate,
                          PlatformTransactionManager transactionManager) {
        this.estimateService = estimateService;
        this.estimateRepository = estimateRepository;
        this.outboxRepository = outboxRepository;
        this.claimService = claimService;
        this.eventProducer = eventProducer;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void clearData() {
        outboxRepository.deleteAll();
        estimateRepository.deleteAll();
    }

    @Test
    void confirmationAndOutboxCommitTogetherWithOriginalEnvelope() throws Exception {
        Long estimateId = draftId();

        estimateService.confirm(estimateId);

        OutboxEvent event = outboxRepository.findAll().get(0);
        JsonNode json = objectMapper.readTree(event.getPayloadJson());
        assertEquals(EstimateStatus.CONFIRMED, estimateRepository.findById(estimateId).orElseThrow().getStatus());
        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals("estimate.parsed", event.getTopic());
        assertEquals(String.valueOf(estimateId), event.getRecordKey());
        assertEquals(event.getEventId(), json.get("eventId").asText());
        assertEquals("ESTIMATE_PARSED", json.get("eventType").asText());
        assertEquals(estimateId.longValue(), json.get("payload").get("estimateId").asLong());
        assertEquals(100L, json.get("payload").get("totalAmount").asLong());
    }

    @Test
    void rollbackRevertsBothConfirmationAndEnqueuedEvent() {
        Long estimateId = draftId();

        transactions.executeWithoutResult(status -> {
            estimateService.confirm(estimateId);
            status.setRollbackOnly();
        });

        assertEquals(EstimateStatus.DRAFT, estimateRepository.findById(estimateId).orElseThrow().getStatus());
        assertEquals(0, outboxRepository.count());
    }

    @Test
    void enqueueRequiresBusinessTransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> eventProducer.sendEstimateParsed(
                EstimateParsedPayload.builder().estimateId(1L).siteId(2L)
                        .totalAmount(new BigDecimal("100.00")).build()));
        assertEquals(0, outboxRepository.count());
    }

    @Test
    @SuppressWarnings("unchecked")
    void brokerAckMarksSentWithoutChangingStoredJson() {
        Long estimateId = draftId();
        estimateService.confirm(estimateId);
        OutboxEvent before = outboxRepository.findAll().get(0);
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(eq("estimate.parsed"), eq(String.valueOf(estimateId)), anyString()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(mock(SendResult.class)));
        OutboxDispatcher dispatcher = new OutboxDispatcher(claimService, template);
        ReflectionTestUtils.setField(dispatcher, "batchSize", 100);

        dispatcher.dispatch();

        OutboxEvent after = outboxRepository.findById(before.getEventId()).orElseThrow();
        assertEquals(OutboxStatus.SENT, after.getStatus());
        assertEquals(before.getPayloadJson(), after.getPayloadJson());
        assertEquals(before.getEventId(), after.getEventId());
        assertTrue(after.getSentAt() != null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void brokerFailureKeepsBusinessChangeAndSchedulesRetry() {
        Long estimateId = draftId();
        estimateService.confirm(estimateId);
        OutboxEvent before = outboxRepository.findAll().get(0);
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(eq("estimate.parsed"), eq(String.valueOf(estimateId)), anyString()))
                .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalStateException("broker unavailable")));
        OutboxDispatcher dispatcher = new OutboxDispatcher(claimService, template);
        ReflectionTestUtils.setField(dispatcher, "batchSize", 100);

        dispatcher.dispatch();

        OutboxEvent after = outboxRepository.findById(before.getEventId()).orElseThrow();
        assertEquals(EstimateStatus.CONFIRMED, estimateRepository.findById(estimateId).orElseThrow().getStatus());
        assertEquals(OutboxStatus.PENDING, after.getStatus());
        assertEquals(1, after.getAttempts());
        assertEquals(before.getPayloadJson(), after.getPayloadJson());
    }

    @Test
    void confirmedDeleteStillEnqueuesDeletionUsingOriginalContract() throws Exception {
        Long estimateId = draftId();
        estimateService.confirm(estimateId);

        estimateService.delete(estimateId);

        assertFalse(estimateRepository.existsById(estimateId));
        assertEquals(2, outboxRepository.count());
        OutboxEvent deletion = outboxRepository.findAll().stream()
                .filter(event -> "estimate.deleted".equals(event.getTopic())).findFirst().orElseThrow();
        JsonNode json = objectMapper.readTree(deletion.getPayloadJson());
        assertEquals("ESTIMATE_DELETED", json.get("eventType").asText());
        assertEquals(String.valueOf(estimateId), deletion.getRecordKey());
        assertEquals(deletion.getEventId(), json.get("eventId").asText());
    }

    @Test
    @SuppressWarnings("unchecked")
    void leaseRecoveryReusesSameEventAfterAckBeforeStateUpdate() throws Exception {
        String eventId = UUID.randomUUID().toString();
        outboxRepository.save(OutboxEvent.pending(eventId, "estimate.parsed", "1",
                "{\"eventId\":\"" + eventId + "\"}", Instant.now()));

        OutboxClaim first = claimService.claimNext().orElseThrow();
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(eq(first.topic()), eq(first.recordKey()), eq(first.payloadJson())))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(mock(SendResult.class)));
        template.send(first.topic(), first.recordKey(), first.payloadJson()).get();
        // Simulate a process crash after broker ACK and before markSent.
        assertTrue(claimService.claimNext().isEmpty());
        jdbcTemplate.update("UPDATE outbox_events SET lease_until = ? WHERE event_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), eventId);

        OutboxClaim recovered = claimService.claimNext().orElseThrow();
        assertNotEquals(first.token(), recovered.token());
        assertEquals(first.eventId(), recovered.eventId());
        assertEquals(first.payloadJson(), recovered.payloadJson());
        assertFalse(claimService.markSent(first));
        assertFalse(claimService.markFailed(first, new IllegalStateException("stale")));
        assertTrue(claimService.markSent(recovered));
        assertEquals(OutboxStatus.SENT, outboxRepository.findById(eventId).orElseThrow().getStatus());
    }

    @Test
    void failureSchedulesBoundedRetry() {
        String eventId = UUID.randomUUID().toString();
        outboxRepository.save(OutboxEvent.pending(eventId, "estimate.parsed", "1", "{}", Instant.now()));

        OutboxClaim claim = claimService.claimNext().orElseThrow();
        assertTrue(claimService.markFailed(claim, new IllegalStateException("broker down")));

        OutboxEvent failed = outboxRepository.findById(eventId).orElseThrow();
        assertEquals(OutboxStatus.PENDING, failed.getStatus());
        assertEquals(1, failed.getAttempts());
        assertTrue(failed.getNextAttemptAt().isAfter(failed.getCreatedAt()));
        assertTrue(claimService.claimNext().isEmpty());
    }

    @Test
    void concurrentDispatchersClaimOnlyOneRow() throws Exception {
        String eventId = UUID.randomUUID().toString();
        outboxRepository.save(OutboxEvent.pending(eventId, "estimate.parsed", "1", "{}", Instant.now()));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Optional<OutboxClaim>>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(workers.submit(() -> {
                    ready.countDown();
                    start.await();
                    return claimService.claimNext();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int claimed = 0;
            for (Future<Optional<OutboxClaim>> result : results) {
                if (result.get(10, TimeUnit.SECONDS).isPresent()) {
                    claimed++;
                }
            }
            assertEquals(1, claimed);
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    private Long draftId() {
        return estimateRepository.saveAndFlush(Estimate.builder()
                .siteId(1L)
                .title("견적")
                .estimateDate(LocalDate.of(2026, 9, 20))
                .totalAmount(new BigDecimal("100.00"))
                .build()).getId();
    }
}
