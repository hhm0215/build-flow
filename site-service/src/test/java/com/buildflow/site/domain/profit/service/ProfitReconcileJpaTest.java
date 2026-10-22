package com.buildflow.site.domain.profit.service;

import com.buildflow.site.domain.profit.dto.ProfitReconcileResponse;
import com.buildflow.site.domain.profit.entity.PurchaseProfitProjection;
import com.buildflow.site.domain.profit.entity.SiteProfit;
import com.buildflow.site.domain.profit.event.ProfitEventType;
import com.buildflow.site.domain.profit.repository.ProcessedProfitEventRepository;
import com.buildflow.site.domain.profit.repository.PurchaseProfitProjectionRepository;
import com.buildflow.site.domain.profit.repository.SiteProfitRepository;
import com.buildflow.site.domain.site.entity.Site;
import com.buildflow.site.domain.site.repository.SiteRepository;
import com.buildflow.site.global.exception.BusinessException;
import com.buildflow.site.global.exception.ErrorCode;
import com.buildflow.site.infra.feign.EstimateProfitSource;
import com.buildflow.site.infra.feign.EstimateProfitSourceClient;
import com.buildflow.site.infra.feign.ProfitSourceResponse;
import com.buildflow.site.infra.feign.PurchaseProfitSource;
import com.buildflow.site.infra.feign.PurchaseProfitSourceClient;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@DataJpaTest
@ActiveProfiles("test")
@Import({ProfitService.class, ProfitReconcileService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProfitReconcileJpaTest {

    @Autowired private ProfitService profitService;
    @Autowired private ProfitReconcileService reconcileService;
    @Autowired private SiteRepository siteRepository;
    @Autowired private SiteProfitRepository profitRepository;
    @Autowired private PurchaseProfitProjectionRepository projectionRepository;
    @Autowired private ProcessedProfitEventRepository processedRepository;
    @MockBean private EstimateProfitSourceClient estimateClient;
    @MockBean private PurchaseProfitSourceClient purchaseClient;

    private Long siteId;

    @BeforeEach
    void setUp() {
        processedRepository.deleteAll();
        projectionRepository.deleteAll();
        profitRepository.deleteAll();
        siteRepository.deleteAll();
        siteId = siteRepository.saveAndFlush(Site.builder().siteName("재계산 현장").build()).getId();
    }

    @Test
    void matchingSourceChangesNothingEvenWithApply() {
        String parsed = UUID.randomUUID().toString();
        profitService.applyEvent(parsed, ProfitEventType.ESTIMATE_PARSED, siteId, amount("1000.00"), null);
        profitService.applyPurchaseEvent(UUID.randomUUID().toString(), ProfitEventType.PURCHASE_REGISTERED,
                11L, siteId, 1L, amount("300.00"));
        stubEstimate("1000.00", event(parsed, "ESTIMATE_PARSED"));
        stubPurchase("300.00", item(11L, 1L, "300.00"));
        long processedBefore = processedRepository.count();

        ProfitReconcileResponse response = reconcileService.reconcile(siteId, true);

        assertTrue(response.isMatched());
        assertFalse(response.isApplied());
        assertEquals(0, response.getEstimateEventsToMark());
        assertEquals(0, response.getPurchaseProjectionsToRewrite());
        assertEquals(processedBefore, processedRepository.count());
        assertAmount("700.00", profit().getMargin());
        assertAmount("70.00", profit().getMarginRate());
    }

    @Test
    void emptySiteWithEmptySourceIsMatchedAndCreatesNoRow() {
        stubEstimate("0");
        stubPurchase("0");

        ProfitReconcileResponse response = reconcileService.reconcile(siteId, true);

        assertTrue(response.isMatched());
        assertFalse(response.isApplied());
        assertEquals(0, profitRepository.count());
    }

    @Test
    void dryRunReportsDriftWithoutChangingAnything() {
        profitService.applyEvent(UUID.randomUUID().toString(), ProfitEventType.ESTIMATE_PARSED,
                siteId, amount("500.00"), null);
        String lost = UUID.randomUUID().toString();
        stubEstimate("1500.00", event(lost, "ESTIMATE_PARSED"));
        stubPurchase("200.00", item(21L, 1L, "200.00"));

        ProfitReconcileResponse response = reconcileService.reconcile(siteId, false);

        assertFalse(response.isMatched());
        assertFalse(response.isApplied());
        assertAmount("500.00", response.getBefore().getTotalEstimateAmount());
        assertAmount("1500.00", response.getSource().getTotalEstimateAmount());
        assertAmount("200.00", response.getSource().getTotalPurchaseAmount());
        assertAmount("1300.00", response.getSource().getMargin());
        assertAmount("86.67", response.getSource().getMarginRate());
        assertEquals(1, response.getEstimateEventsToMark());
        assertEquals(1, response.getPurchaseProjectionsToRewrite());
        assertAmount("500.00", profit().getTotalEstimateAmount());
        assertFalse(processedRepository.existsById(lost));
        assertEquals(0, projectionRepository.count());
    }

    @Test
    void applyRewritesTotalsProjectionsAndIgnoresLateDuplicateEvents() {
        String applied = UUID.randomUUID().toString();
        profitService.applyEvent(applied, ProfitEventType.ESTIMATE_PARSED, siteId, amount("1000.00"), null);
        // 원천에는 반영됐지만 site에 도착하지 않은 이벤트(대체 + 수정본 확정)
        String supersededDeleted = UUID.randomUUID().toString();
        String revisionParsed = UUID.randomUUID().toString();
        // 원천에서 삭제된 매입 99는 site projection에만 활성으로 남아 있다
        profitService.applyPurchaseEvent(UUID.randomUUID().toString(), ProfitEventType.PURCHASE_REGISTERED,
                99L, siteId, 1L, amount("50.00"));
        stubEstimate("1200.00", event(applied, "ESTIMATE_PARSED"),
                event(supersededDeleted, "ESTIMATE_DELETED"), event(revisionParsed, "ESTIMATE_PARSED"));
        stubPurchase("400.00", item(11L, 2L, "400.00"));

        ProfitReconcileResponse response = reconcileService.reconcile(siteId, true);

        assertTrue(response.isApplied());
        assertEquals(2, response.getEstimateEventsToMark());
        assertEquals(2, response.getPurchaseProjectionsToRewrite());
        assertAmount("1200.00", profit().getTotalEstimateAmount());
        assertAmount("400.00", profit().getTotalPurchaseAmount());
        assertAmount("800.00", profit().getMargin());
        assertAmount("66.67", profit().getMarginRate());
        PurchaseProfitProjection rewritten = projectionRepository.findById(11L).orElseThrow();
        assertEquals(2L, rewritten.getLastRevision());
        assertEquals(response.getReconcileId(), rewritten.getLastEventId());
        PurchaseProfitProjection tombstone = projectionRepository.findById(99L).orElseThrow();
        assertTrue(tombstone.isDeleted());

        // 재계산 직후 늦게 도착한 같은 이벤트는 eventId·revision 규칙으로 무시된다
        assertFalse(profitService.applyEvent(supersededDeleted, ProfitEventType.ESTIMATE_DELETED,
                siteId, amount("1000.00"), null));
        assertFalse(profitService.applyEvent(revisionParsed, ProfitEventType.ESTIMATE_PARSED,
                siteId, amount("1200.00"), null));
        assertFalse(profitService.applyPurchaseEvent(UUID.randomUUID().toString(),
                ProfitEventType.PURCHASE_REGISTERED, 11L, siteId, 1L, amount("100.00")));
        assertFalse(profitService.applyPurchaseEvent(UUID.randomUUID().toString(),
                ProfitEventType.PURCHASE_UPDATED, 11L, siteId, 2L, amount("400.00")));
        assertFalse(profitService.applyPurchaseEvent(UUID.randomUUID().toString(),
                ProfitEventType.PURCHASE_UPDATED, 99L, siteId, 2L, amount("70.00")));
        assertFalse(profitService.applyPurchaseEvent(UUID.randomUUID().toString(),
                ProfitEventType.PURCHASE_DELETED, 99L, siteId, 3L, amount("70.00")));
        assertAmount("1200.00", profit().getTotalEstimateAmount());
        assertAmount("400.00", profit().getTotalPurchaseAmount());

        // 스냅샷 이후의 새 이벤트는 평소처럼 증분 반영된다
        assertTrue(profitService.applyPurchaseEvent(UUID.randomUUID().toString(),
                ProfitEventType.PURCHASE_UPDATED, 11L, siteId, 3L, amount("450.00")));
        assertAmount("450.00", profit().getTotalPurchaseAmount());

        // 다시 재계산하면 원천과 일치(3 → 원천도 450으로 바뀐 상태)
        stubPurchase("450.00", item(11L, 3L, "450.00"));
        assertTrue(reconcileService.reconcile(siteId, false).isMatched());
    }

    @Test
    void supersededEstimatesStayOutBecauseOnlyConfirmedSumIsUsed() {
        // site가 대체(SUPERSEDED) 이벤트를 놓쳐 원본+수정본이 모두 합산된 상태
        String original = UUID.randomUUID().toString();
        String revision = UUID.randomUUID().toString();
        String superseded = UUID.randomUUID().toString();
        profitService.applyEvent(original, ProfitEventType.ESTIMATE_PARSED, siteId, amount("1000.00"), null);
        profitService.applyEvent(revision, ProfitEventType.ESTIMATE_PARSED, siteId, amount("1200.00"), null);
        stubEstimate("1200.00", event(original, "ESTIMATE_PARSED"), event(revision, "ESTIMATE_PARSED"),
                event(superseded, "ESTIMATE_DELETED"));
        stubPurchase("0");

        ProfitReconcileResponse response = reconcileService.reconcile(siteId, true);

        assertAmount("2200.00", response.getBefore().getTotalEstimateAmount());
        assertAmount("1200.00", profit().getTotalEstimateAmount());
        assertEquals(1, response.getEstimateEventsToMark());
        assertFalse(profitService.applyEvent(superseded, ProfitEventType.ESTIMATE_DELETED,
                siteId, amount("1000.00"), null));
        assertAmount("1200.00", profit().getTotalEstimateAmount());
    }

    @Test
    void sourceFailureIsServiceUnavailableAndChangesNothing() {
        profitService.applyEvent(UUID.randomUUID().toString(), ProfitEventType.ESTIMATE_PARSED,
                siteId, amount("500.00"), null);
        stubEstimate("900.00");
        when(purchaseClient.snapshot(anyLong())).thenThrow(new FeignException.ServiceUnavailable(
                "down", Request.create(Request.HttpMethod.GET, "/api/v1/purchases/profit-source",
                        new HashMap<>(), null, StandardCharsets.UTF_8, null), null, null));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> reconcileService.reconcile(siteId, true));

        assertEquals(ErrorCode.PROFIT_SOURCE_UNAVAILABLE, failure.getErrorCode());
        assertEquals(503, failure.getErrorCode().getStatus().value());
        assertAmount("500.00", profit().getTotalEstimateAmount());
    }

    @Test
    void malformedSourceIsServiceUnavailable() {
        stubEstimate("100.00", event("not-a-uuid", "ESTIMATE_PARSED"));
        stubPurchase("0");
        assertEquals(ErrorCode.PROFIT_SOURCE_UNAVAILABLE, assertThrows(BusinessException.class,
                () -> reconcileService.reconcile(siteId, true)).getErrorCode());

        stubEstimate("100.00");
        stubPurchase("10.00", item(1L, 1L, "20.00"));   // 합계와 항목 불일치
        assertEquals(ErrorCode.PROFIT_SOURCE_UNAVAILABLE, assertThrows(BusinessException.class,
                () -> reconcileService.reconcile(siteId, true)).getErrorCode());

        when(estimateClient.snapshot(anyLong())).thenReturn(new ProfitSourceResponse<>(false, null));
        assertEquals(ErrorCode.PROFIT_SOURCE_UNAVAILABLE, assertThrows(BusinessException.class,
                () -> reconcileService.reconcile(siteId, true)).getErrorCode());
        assertEquals(0, profitRepository.count());
    }

    @Test
    void extremeTotalsKeepMarginRateWithinColumnAndOverflowIsConflict() {
        stubEstimate("1.00");
        stubPurchase("9999999999999.99", item(1L, 1L, "9999999999999.99"));

        reconcileService.reconcile(siteId, true);
        profitRepository.flush();

        assertAmount("-999.99", profit().getMarginRate());
        assertAmount("-9999999999998.99", profit().getMargin());

        stubPurchase("10000000000000.00", item(1L, 1L, "9999999999999.99"), item(2L, 1L, "0.01"));
        assertEquals(ErrorCode.PROFIT_RECONCILE_CONFLICT, assertThrows(BusinessException.class,
                () -> reconcileService.reconcile(siteId, true)).getErrorCode());
    }

    @Test
    void unknownSiteIsNotFound() {
        assertEquals(ErrorCode.SITE_NOT_FOUND, assertThrows(BusinessException.class,
                () -> reconcileService.reconcile(siteId + 1000, false)).getErrorCode());
    }

    private void stubEstimate(String total, EstimateProfitSource.Event... events) {
        when(estimateClient.snapshot(anyLong())).thenReturn(new ProfitSourceResponse<>(true,
                new EstimateProfitSource(siteId, amount(total), List.of(events))));
    }

    private void stubPurchase(String total, PurchaseProfitSource.Item... items) {
        when(purchaseClient.snapshot(anyLong())).thenReturn(new ProfitSourceResponse<>(true,
                new PurchaseProfitSource(siteId, amount(total), List.of(items))));
    }

    private EstimateProfitSource.Event event(String eventId, String type) {
        return new EstimateProfitSource.Event(eventId, type);
    }

    private PurchaseProfitSource.Item item(Long purchaseId, Long revision, String amount) {
        return new PurchaseProfitSource.Item(purchaseId, revision, amount(amount));
    }

    private SiteProfit profit() {
        return profitRepository.findBySiteId(siteId).orElseThrow();
    }

    private BigDecimal amount(String value) {
        return new BigDecimal(value);
    }

    private void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, actual.compareTo(new BigDecimal(expected)), () -> expected + " != " + actual);
    }
}
