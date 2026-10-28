package com.buildflow.estimate.domain.estimate.service;

import com.buildflow.estimate.domain.estimate.dto.EstimateCreateRequest;
import com.buildflow.estimate.domain.estimate.dto.EstimateResponse;
import com.buildflow.estimate.domain.estimate.dto.EstimateUpdateRequest;
import com.buildflow.estimate.domain.estimate.entity.Estimate;
import com.buildflow.estimate.domain.estimate.entity.EstimateKind;
import com.buildflow.estimate.domain.estimate.entity.EstimateStatus;
import com.buildflow.estimate.domain.estimate.repository.EstimateRepository;
import com.buildflow.estimate.global.exception.BusinessException;
import com.buildflow.estimate.global.exception.ErrorCode;
import com.buildflow.estimate.global.outbox.OutboxEvent;
import com.buildflow.estimate.global.outbox.OutboxEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;

/**
 * 견적 수정본·추가공사 체인: 한 수정 체인에서 손익에 반영되는 확정본은 최대 1개.
 * site-service 손익은 outbox 이벤트(+parsed / -deleted)의 합으로 재현해 검증한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EstimateRevisionIntegrationTest {

    private static final long SITE = 1L;
    private static final long OTHER_SITE = 2L;

    private final EstimateService estimateService;
    private final EstimateRepository estimateRepository;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;

    @MockBean private SiteReferenceValidator siteReferenceValidator;

    EstimateRevisionIntegrationTest(EstimateService estimateService, EstimateRepository estimateRepository,
                                    OutboxEventRepository outboxRepository, ObjectMapper objectMapper,
                                    JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.estimateService = estimateService;
        this.estimateRepository = estimateRepository;
        this.outboxRepository = outboxRepository;
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
    void confirmingRevisionSupersedesPreviousAndProfitEqualsLatestConfirmed() throws Exception {
        Long original = confirmed(create(SITE, null, null, "1000"));
        Long revision1 = create(SITE, EstimateKind.REVISION, original, "1200");
        estimateService.confirm(revision1);

        assertEquals(EstimateStatus.SUPERSEDED, status(original));
        assertEquals(revision1, estimateRepository.findById(original).orElseThrow().getSupersededById());
        assertEquals(new BigDecimal("1200.00"), projectedEstimateTotal(SITE));

        // 대체된 견적에서 다시 수정본을 만들어도 같은 체인이며, 최신 확정본 하나만 남는다.
        Long revision2 = create(SITE, EstimateKind.REVISION, original, "900");
        EstimateResponse response = estimateService.confirm(revision2);

        assertEquals(original, response.getChainRootId());
        assertEquals(EstimateStatus.SUPERSEDED, status(revision1));
        assertEquals(EstimateStatus.SUPERSEDED, status(original));
        assertEquals(new BigDecimal("900.00"), projectedEstimateTotal(SITE));
        assertEquals(1, confirmedCount());

        JsonNode supersede = deletedEvents().stream()
                .map(json -> json.get("payload"))
                .filter(payload -> payload.get("estimateId").asLong() == original)
                .findFirst().orElseThrow();
        assertEquals("SUPERSEDED", supersede.get("reason").asText());
        assertEquals(original.longValue(), supersede.get("estimateId").asLong());
        assertEquals(revision1.longValue(), supersede.get("supersededById").asLong());
        // 기존 estimate.parsed 계약은 그대로 — 선택 필드는 null이면 직렬화하지 않는다.
        JsonNode parsed = events("estimate.parsed").get(0).get("payload");
        assertFalse(parsed.has("reason"));
    }

    @Test
    void additionalWorkIsSummedAndItsRevisionReplacesOnlyTheAdditional() throws Exception {
        Long original = confirmed(create(SITE, null, null, "1000"));
        Long additional = confirmed(create(SITE, EstimateKind.ADDITIONAL, original, "300"));

        assertEquals(EstimateStatus.CONFIRMED, status(original));
        assertEquals(new BigDecimal("1300.00"), projectedEstimateTotal(SITE));

        Long additionalRevision = confirmed(create(SITE, EstimateKind.REVISION, additional, "350"));

        assertEquals(EstimateStatus.CONFIRMED, status(original));
        assertEquals(EstimateStatus.SUPERSEDED, status(additional));
        assertEquals(EstimateStatus.CONFIRMED, status(additionalRevision));
        assertEquals(additional, estimateRepository.findById(additionalRevision).orElseThrow().getChainRootId());
        assertEquals(new BigDecimal("1350.00"), projectedEstimateTotal(SITE));
    }

    @Test
    void invalidRelationsAreRejected() throws Exception {
        Long otherSite = confirmed(create(OTHER_SITE, null, null, "1000"));
        Long draft = create(SITE, null, null, "1000");
        Long confirmed = confirmed(create(SITE, null, null, "1000"));

        assertRelationError(() -> create(SITE, EstimateKind.REVISION, otherSite, "1"));
        assertRelationError(() -> create(SITE, EstimateKind.ADDITIONAL, otherSite, "1"));
        assertRelationError(() -> create(SITE, EstimateKind.REVISION, draft, "1"));
        assertRelationError(() -> create(SITE, EstimateKind.REVISION, 999_999L, "1"));
        assertRelationError(() -> create(SITE, EstimateKind.REVISION, null, "1"));
        assertRelationError(() -> create(SITE, EstimateKind.ORIGINAL, confirmed, "1"));
        assertRelationError(() -> create(SITE, null, confirmed, "1"));
        assertEquals(3, estimateRepository.count());
    }

    @Test
    void draftRevisionDeletesFreelyButSupersededHistoryIsImmutable() throws Exception {
        Long original = confirmed(create(SITE, null, null, "1000"));
        Long draftRevision = create(SITE, EstimateKind.REVISION, original, "1100");
        estimateService.delete(draftRevision);
        assertFalse(estimateRepository.existsById(draftRevision));
        assertEquals(EstimateStatus.CONFIRMED, status(original));

        Long revision = confirmed(create(SITE, EstimateKind.REVISION, original, "1200"));

        assertEquals(ErrorCode.ESTIMATE_SUPERSEDED,
                assertThrows(BusinessException.class, () -> estimateService.delete(original)).getErrorCode());
        assertEquals(ErrorCode.ESTIMATE_SUPERSEDED,
                assertThrows(BusinessException.class, () -> estimateService.confirm(original)).getErrorCode());
        assertEquals(ErrorCode.ESTIMATE_SUPERSEDED,
                assertThrows(BusinessException.class,
                        () -> estimateService.update(original, updateRequest())).getErrorCode());
        assertEquals(ErrorCode.CONFIRMED_ESTIMATE_DELETE_NOT_ALLOWED,
                assertThrows(BusinessException.class, () -> estimateService.delete(revision)).getErrorCode());
        assertEquals(new BigDecimal("1000.00"),
                estimateRepository.findById(original).orElseThrow().getTotalAmount());
        assertEquals(new BigDecimal("1200.00"), projectedEstimateTotal(SITE));
    }

    @Test
    void archivedSiteRejectsRevisionConfirmWithoutSupersedingOrPublishing() throws Exception {
        Long original = confirmed(create(SITE, null, null, "1000"));
        Long revision = create(SITE, EstimateKind.REVISION, original, "1200");
        long outboxBefore = outboxRepository.count();
        doThrow(new BusinessException(ErrorCode.SITE_ARCHIVED_DOCUMENT_LOCKED))
                .when(siteReferenceValidator).requireSiteNotArchived(SITE);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> estimateService.confirm(revision));

        assertEquals(ErrorCode.SITE_ARCHIVED_DOCUMENT_LOCKED, exception.getErrorCode());
        assertEquals(409, exception.getErrorCode().getStatus().value());
        assertEquals(EstimateStatus.CONFIRMED, status(original));
        assertEquals(EstimateStatus.DRAFT, status(revision));
        assertEquals(outboxBefore, outboxRepository.count());
    }

    @Test
    void concurrentRevisionConfirmsAreSerializedByChainRootLock() throws Exception {
        Long original = confirmed(create(SITE, null, null, "1000"));
        Long revisionA = create(SITE, EstimateKind.REVISION, original, "1100");
        Long revisionB = create(SITE, EstimateKind.REVISION, original, "1300");
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> transactions.executeWithoutResult(ignored -> {
                estimateService.confirm(revisionA);
                lockHeld.countDown();
                await(releaseLock);
            }));
            assertTrue(lockHeld.await(5, TimeUnit.SECONDS));

            Future<?> second = executor.submit(() -> estimateService.confirm(revisionB));
            assertTrue(awaitBlockedSessions(1));
            assertFalse(second.isDone());

            releaseLock.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }

        assertEquals(EstimateStatus.SUPERSEDED, status(original));
        assertEquals(EstimateStatus.SUPERSEDED, status(revisionA));
        assertEquals(EstimateStatus.CONFIRMED, status(revisionB));
        assertEquals(1, confirmedCount());
        assertEquals(new BigDecimal("1300.00"), projectedEstimateTotal(SITE));
    }

    @Test
    void siteArchivedBetweenValidationAndCommitRollsBackNewRevision() throws Exception {
        Long original = confirmed(create(SITE, null, null, "1000"));
        long estimatesBefore = estimateRepository.count();
        long outboxBefore = outboxRepository.count();
        doThrow(new BusinessException(ErrorCode.SITE_ARCHIVED))
                .when(siteReferenceValidator).recheckBeforeCommit(SITE);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> create(SITE, EstimateKind.REVISION, original, "1200"));

        assertEquals(ErrorCode.SITE_ARCHIVED, exception.getErrorCode());
        assertEquals(estimatesBefore, estimateRepository.count());
        assertEquals(0, jdbcTemplate.queryForObject(
                "select count(*) from estimate_items i join estimates e on e.id = i.estimate_id where e.revision_of_id = ?",
                Long.class, original));
        assertEquals(outboxBefore, outboxRepository.count());
    }

    @Test
    void siteArchivedBeforeConfirmCommitRollsBackSupersedeAndProfitEvents() throws Exception {
        Long original = confirmed(create(SITE, null, null, "1000"));
        Long revision = create(SITE, EstimateKind.REVISION, original, "1200");
        long outboxBefore = outboxRepository.count();
        doThrow(new BusinessException(ErrorCode.SITE_ARCHIVED_DOCUMENT_LOCKED))
                .when(siteReferenceValidator).recheckNotArchivedBeforeCommit(SITE);

        BusinessException exception = assertThrows(BusinessException.class, () -> estimateService.confirm(revision));

        assertEquals(ErrorCode.SITE_ARCHIVED_DOCUMENT_LOCKED, exception.getErrorCode());
        assertEquals(EstimateStatus.CONFIRMED, status(original));
        assertEquals(null, estimateRepository.findById(original).orElseThrow().getSupersededById());
        assertEquals(EstimateStatus.DRAFT, status(revision));
        assertEquals(outboxBefore, outboxRepository.count());
        assertEquals(new BigDecimal("1000.00"), projectedEstimateTotal(SITE));
    }

    @Test
    void siteArchivedBeforeUpdateOrDeleteCommitKeepsDraftUnchanged() throws Exception {
        Long draft = create(SITE, null, null, "1000");
        doThrow(new BusinessException(ErrorCode.SITE_ARCHIVED_DOCUMENT_LOCKED))
                .when(siteReferenceValidator).recheckNotArchivedBeforeCommit(SITE);

        assertThrows(BusinessException.class, () -> estimateService.update(draft, updateRequest()));
        Estimate unchanged = estimateRepository.findById(draft).orElseThrow();
        assertEquals("견적", unchanged.getTitle());
        assertEquals(new BigDecimal("1000.00"), unchanged.getTotalAmount());

        assertThrows(BusinessException.class, () -> estimateService.delete(draft));
        assertTrue(estimateRepository.existsById(draft));
        assertEquals(0, outboxRepository.count());
    }

    private Long create(long siteId, EstimateKind kind, Long revisionOfId, String unitPrice) throws Exception {
        String json = objectMapper.writeValueAsString(new java.util.LinkedHashMap<String, Object>() {{
            put("siteId", siteId);
            put("title", "견적");
            put("estimateDate", "2026-10-23");
            put("items", List.of(java.util.Map.of(
                    "itemName", "공사", "unit", "식", "quantity", 1, "unitPrice", new BigDecimal(unitPrice))));
            if (kind != null) put("kind", kind.name());
            if (revisionOfId != null) put("revisionOfId", revisionOfId);
        }});
        return estimateService.create(objectMapper.readValue(json, EstimateCreateRequest.class)).getId();
    }

    private Long confirmed(Long id) {
        estimateService.confirm(id);
        return id;
    }

    private EstimateUpdateRequest updateRequest() throws Exception {
        return objectMapper.readValue("{\"title\":\"수정\",\"estimateDate\":\"2026-10-23\","
                + "\"items\":[{\"itemName\":\"공사\",\"unit\":\"식\",\"quantity\":1,\"unitPrice\":1}]}",
                EstimateUpdateRequest.class);
    }

    private void assertRelationError(ThrowingRunnable runnable) {
        BusinessException exception = assertThrows(BusinessException.class, runnable::run);
        assertEquals(ErrorCode.INVALID_ESTIMATE_RELATION, exception.getErrorCode());
        assertEquals(400, exception.getErrorCode().getStatus().value());
    }

    private EstimateStatus status(Long id) {
        return estimateRepository.findById(id).orElseThrow().getStatus();
    }

    private long confirmedCount() {
        return estimateRepository.findAll().stream()
                .filter(estimate -> estimate.getSiteId() == SITE)
                .map(Estimate::getStatus)
                .filter(EstimateStatus.CONFIRMED::equals)
                .count();
    }

    /** site-service ProfitService와 같은 규칙: parsed는 더하고 deleted는 뺀다. */
    private BigDecimal projectedEstimateTotal(long siteId) throws Exception {
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (OutboxEvent event : outboxRepository.findAll()) {
            JsonNode payload = objectMapper.readTree(event.getPayloadJson()).get("payload");
            if (payload.get("siteId").asLong() != siteId) continue;
            BigDecimal amount = new BigDecimal(payload.get("totalAmount").asText());
            total = "estimate.deleted".equals(event.getTopic()) ? total.subtract(amount) : total.add(amount);
        }
        return total;
    }

    private List<JsonNode> deletedEvents() throws Exception {
        return events("estimate.deleted");
    }

    private List<JsonNode> events(String topic) throws Exception {
        List<JsonNode> result = new java.util.ArrayList<>();
        for (OutboxEvent event : outboxRepository.findAll()) {
            if (topic.equals(event.getTopic())) {
                JsonNode json = objectMapper.readTree(event.getPayloadJson());
                assertEquals("estimate.deleted".equals(topic) ? "ESTIMATE_DELETED" : "ESTIMATE_PARSED",
                        json.get("eventType").asText());
                result.add(json);
            }
        }
        return result;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("lock release timeout");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private boolean awaitBlockedSessions(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            Integer blocked = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL",
                    Integer.class);
            if (blocked != null && blocked >= expected) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.nanoTime() < deadline);
        return false;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
