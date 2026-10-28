package com.buildflow.estimate.domain.estimate.service;

import com.buildflow.estimate.domain.estimate.dto.EstimateCreateRequest;
import com.buildflow.estimate.domain.estimate.dto.EstimateProfitSourceResponse;
import com.buildflow.estimate.domain.estimate.entity.EstimateKind;
import com.buildflow.estimate.domain.estimate.repository.EstimateRepository;
import com.buildflow.estimate.global.exception.BusinessException;
import com.buildflow.estimate.global.exception.ErrorCode;
import com.buildflow.estimate.global.outbox.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 손익 재계산 원천 스냅샷: CONFIRMED만 합산하고, 합계에 반영된 손익 eventId를 모두 돌려준다. */
@SpringBootTest
@ActiveProfiles("test")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EstimateProfitSourceIntegrationTest {

    private static final long SITE = 1L;
    private static final long OTHER_SITE = 2L;

    private final EstimateService estimateService;
    private final EstimateProfitSourceService sourceService;
    private final EstimateRepository estimateRepository;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @MockBean private SiteReferenceValidator siteReferenceValidator;

    EstimateProfitSourceIntegrationTest(EstimateService estimateService, EstimateProfitSourceService sourceService,
                                        EstimateRepository estimateRepository,
                                        OutboxEventRepository outboxRepository, ObjectMapper objectMapper) {
        this.estimateService = estimateService;
        this.sourceService = sourceService;
        this.estimateRepository = estimateRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @BeforeEach
    void clearData() {
        outboxRepository.deleteAll();
        estimateRepository.deleteAll();
    }

    @Test
    void sumsOnlyConfirmedAndListsEveryProfitEventOfTheSite() throws Exception {
        Long original = create(SITE, null, null, "1000");
        estimateService.confirm(original);
        Long revision = create(SITE, EstimateKind.REVISION, original, "1200");
        estimateService.confirm(revision);            // original → SUPERSEDED (+ estimate.deleted)
        create(SITE, null, null, "777");              // 초안: 손익 제외, 이벤트 없음
        Long other = create(OTHER_SITE, null, null, "500");
        estimateService.confirm(other);

        EstimateProfitSourceResponse source = sourceService.snapshot(SITE);

        assertEquals(SITE, source.getSiteId());
        assertEquals(0, new BigDecimal("1200.00").compareTo(source.getConfirmedTotalAmount()));
        assertEquals(1, source.getConfirmedEstimateCount());
        Map<String, Long> byType = source.getProfitEvents().stream()
                .collect(Collectors.groupingBy(EstimateProfitSourceResponse.ProfitEvent::getEventType,
                        Collectors.counting()));
        assertEquals(2L, byType.get("ESTIMATE_PARSED"));
        assertEquals(1L, byType.get("ESTIMATE_DELETED"));

        Set<String> siteEventIds = source.getProfitEvents().stream()
                .map(EstimateProfitSourceResponse.ProfitEvent::getEventId)
                .collect(Collectors.toSet());
        Set<String> otherEventIds = sourceService.snapshot(OTHER_SITE).getProfitEvents().stream()
                .map(EstimateProfitSourceResponse.ProfitEvent::getEventId)
                .collect(Collectors.toSet());
        assertEquals(1, otherEventIds.size());
        assertTrue(siteEventIds.stream().noneMatch(otherEventIds::contains));
        assertEquals(0, source.getOrphanProfitEventCount());
    }

    @Test
    void eventsWhoseEstimateRowIsGoneAreCountedPerSite() throws Exception {
        Long kept = create(SITE, null, null, "300");
        estimateService.confirm(kept);
        // 확정 삭제가 허용되던 기간: 견적 행은 지워지고 estimate.deleted만 남은 경우
        outboxRepository.save(com.buildflow.estimate.global.outbox.OutboxEvent.pending(
                java.util.UUID.randomUUID().toString(), "estimate.deleted", "999999",
                "{\"eventType\":\"ESTIMATE_DELETED\",\"payload\":{\"estimateId\":999999,\"siteId\":" + SITE
                        + ",\"totalAmount\":100.00}}", java.time.Instant.now()));
        outboxRepository.save(com.buildflow.estimate.global.outbox.OutboxEvent.pending(
                java.util.UUID.randomUUID().toString(), "estimate.deleted", "888888",
                "{\"eventType\":\"ESTIMATE_DELETED\",\"payload\":{\"estimateId\":888888,\"siteId\":" + OTHER_SITE
                        + ",\"totalAmount\":100.00}}", java.time.Instant.now()));
        outboxRepository.save(com.buildflow.estimate.global.outbox.OutboxEvent.pending(
                java.util.UUID.randomUUID().toString(), "estimate.parsed", "777777", "not-json",
                java.time.Instant.now()));

        EstimateProfitSourceResponse source = sourceService.snapshot(SITE);

        assertEquals(2, source.getOrphanProfitEventCount());   // 이 현장 1 + 읽을 수 없는 payload 1
        assertEquals(1, source.getProfitEvents().size());
        assertEquals(2, sourceService.snapshot(OTHER_SITE).getOrphanProfitEventCount());
        assertEquals(1, sourceService.snapshot(99L).getOrphanProfitEventCount());   // 읽을 수 없는 payload만
    }

    @Test
    void malformedRecordKeyIsNotMistakenForExistingEstimate() throws Exception {
        Long existing = create(SITE, null, null, "300");
        estimateService.confirm(existing);
        outboxRepository.save(com.buildflow.estimate.global.outbox.OutboxEvent.pending(
                java.util.UUID.randomUUID().toString(), "estimate.deleted", existing + "x",
                "{\"payload\":{\"siteId\":" + SITE + "}}", java.time.Instant.now()));

        EstimateProfitSourceResponse source = sourceService.snapshot(SITE);

        assertEquals(1, source.getOrphanProfitEventCount());
        assertEquals(1, source.getProfitEvents().size());
    }

    @Test
    void emptySiteReturnsZeroAndInvalidSiteIsRejected() {
        EstimateProfitSourceResponse empty = sourceService.snapshot(99L);
        assertEquals(0, BigDecimal.ZERO.compareTo(empty.getConfirmedTotalAmount()));
        assertTrue(empty.getProfitEvents().isEmpty());

        BusinessException invalid = assertThrows(BusinessException.class, () -> sourceService.snapshot(0L));
        assertEquals(ErrorCode.INVALID_SITE_REFERENCE, invalid.getErrorCode());
    }

    private Long create(long siteId, EstimateKind kind, Long revisionOfId, String unitPrice) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("siteId", siteId);
        body.put("title", "견적");
        body.put("estimateDate", "2026-10-23");
        body.put("items", List.of(Map.of(
                "itemName", "공사", "unit", "식", "quantity", 1, "unitPrice", new BigDecimal(unitPrice))));
        if (kind != null) body.put("kind", kind.name());
        if (revisionOfId != null) body.put("revisionOfId", revisionOfId);
        String json = objectMapper.writeValueAsString(body);
        return estimateService.create(objectMapper.readValue(json, EstimateCreateRequest.class)).getId();
    }
}
