package com.buildflow.site.domain.profit.service;

import com.buildflow.site.domain.profit.dto.ProfitReconcileResponse;
import com.buildflow.site.domain.profit.dto.ProfitReconcileResponse.Totals;
import com.buildflow.site.domain.profit.entity.ProcessedProfitEvent;
import com.buildflow.site.domain.profit.entity.PurchaseProfitProjection;
import com.buildflow.site.domain.profit.entity.SiteProfit;
import com.buildflow.site.domain.profit.event.ProfitEventType;
import com.buildflow.site.domain.profit.repository.ProcessedProfitEventRepository;
import com.buildflow.site.domain.profit.repository.PurchaseProfitProjectionRepository;
import com.buildflow.site.domain.profit.repository.SiteProfitRepository;
import com.buildflow.site.domain.site.repository.SiteRepository;
import com.buildflow.site.global.exception.BusinessException;
import com.buildflow.site.global.exception.ErrorCode;
import com.buildflow.site.infra.feign.EstimateProfitSource;
import com.buildflow.site.infra.feign.EstimateProfitSourceClient;
import com.buildflow.site.infra.feign.ProfitSourceResponse;
import com.buildflow.site.infra.feign.PurchaseProfitSource;
import com.buildflow.site.infra.feign.PurchaseProfitSourceClient;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 운영 절차용 현장 손익 재계산(docs/DATABASE_OPERATIONS.md "복원 후 재조정"의 UNEXPLAINED 조치).
 *
 * <p>경합 처리: 손익 소비자와 같은 현장 행 비관적 잠금을 먼저 잡은 뒤 원천을 조회하므로, 원천 스냅샷과
 * 반영 사이에 이 현장의 이벤트가 처리되지 않는다. 원천 스냅샷에 보이는 견적 손익 이벤트는 이미 합계에
 * 포함되어 있으므로 수신기록(processed_profit_events)에 함께 기록해 늦게 도착해도 eventId 멱등으로 무시되게 하고,
 * 매입은 projection을 원천 revision으로 다시 써서 같거나 낮은 revision 이벤트가 revision 규칙으로 무시되게 한다.
 * 스냅샷 이후 생긴 이벤트는 목록·합계 어디에도 없으므로 평소처럼 증분 반영된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfitReconcileService {

    static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999.99");

    private final SiteRepository siteRepository;
    private final SiteProfitRepository siteProfitRepository;
    private final ProcessedProfitEventRepository processedEventRepository;
    private final PurchaseProfitProjectionRepository projectionRepository;
    private final EstimateProfitSourceClient estimateSourceClient;
    private final PurchaseProfitSourceClient purchaseSourceClient;

    @Transactional
    public ProfitReconcileResponse reconcile(Long siteId, boolean apply) {
        if (siteId == null || siteId <= 0) {
            throw new BusinessException(ErrorCode.SITE_NOT_FOUND);
        }
        siteRepository.findByIdForUpdate(siteId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SITE_NOT_FOUND));

        EstimateProfitSource estimate = validEstimate(siteId,
                fetch(() -> estimateSourceClient.snapshot(siteId), "estimate", siteId));
        PurchaseProfitSource purchase = validPurchase(siteId,
                fetch(() -> purchaseSourceClient.snapshot(siteId), "purchase", siteId));
        String reconcileId = UUID.randomUUID().toString();

        SiteProfit current = siteProfitRepository.findBySiteId(siteId).orElse(null);
        SiteProfit expected = SiteProfit.builder().siteId(siteId).build();   // 저장하지 않는 계산용
        expected.overwriteTotals(estimate.confirmedTotalAmount(), purchase.totalAmount());
        Totals before = Totals.of(current == null ? SiteProfit.builder().siteId(siteId).build() : current);
        Totals source = Totals.of(expected);

        List<EstimateProfitSource.Event> eventsToMark = unprocessedEvents(estimate.profitEvents());
        ProjectionPlan plan = planProjections(siteId, purchase.purchases());

        boolean matched = before.sameAs(source) && eventsToMark.isEmpty() && plan.size() == 0;
        int orphanEvents = estimate.orphanProfitEventCount();
        if (apply && !matched && orphanEvents > 0) {
            // 견적 행이 사라진 손익 이벤트(과거 확정 삭제 등)는 eventId 목록에 없어 늦게 도착하면 이중 반영될 수 있다
            log.warn("견적 행 없는 손익 이벤트가 있어 재계산 반영 거부: reconcileId={}, siteId={}, orphanEvents={}",
                    reconcileId, siteId, orphanEvents);
            throw new BusinessException(ErrorCode.PROFIT_RECONCILE_CONFLICT);
        }
        boolean applied = apply && !matched;
        if (applied) {
            processedEventRepository.saveAll(eventsToMark.stream()
                    .map(event -> new ProcessedProfitEvent(event.eventId(), siteId, event.eventType()))
                    .toList());
            plan.apply(siteId, reconcileId, projectionRepository);
            SiteProfit target = current != null ? current
                    : siteProfitRepository.save(SiteProfit.builder().siteId(siteId).build());
            target.overwriteTotals(estimate.confirmedTotalAmount(), purchase.totalAmount());
            siteProfitRepository.flush();
        }

        log.info("손익 재계산: reconcileId={}, siteId={}, apply={}, applied={}, matched={}, "
                        + "before=[estimate={}, purchase={}, margin={}, marginRate={}], "
                        + "source=[estimate={}, purchase={}, margin={}, marginRate={}], "
                        + "estimateEventsToMark={}, purchaseProjectionsToRewrite={}, orphanEstimateEvents={}",
                reconcileId, siteId, apply, applied, matched,
                before.getTotalEstimateAmount(), before.getTotalPurchaseAmount(),
                before.getMargin(), before.getMarginRate(),
                source.getTotalEstimateAmount(), source.getTotalPurchaseAmount(),
                source.getMargin(), source.getMarginRate(),
                eventsToMark.size(), plan.size(), orphanEvents);

        return ProfitReconcileResponse.builder()
                .siteId(siteId)
                .applyRequested(apply)
                .applied(applied)
                .matched(matched)
                .reconcileId(reconcileId)
                .before(before)
                .source(source)
                .estimateEventsToMark(eventsToMark.size())
                .purchaseProjectionsToRewrite(plan.size())
                .orphanEstimateEvents(orphanEvents)
                .build();
    }

    private <T> T fetch(Supplier<ProfitSourceResponse<T>> call, String sourceName, Long siteId) {
        ProfitSourceResponse<T> response;
        try {
            response = call.get();
        } catch (FeignException e) {
            log.warn("손익 원천 조회 실패(재계산 중단): source={}, siteId={}, status={}",
                    sourceName, siteId, e.status());
            throw new BusinessException(ErrorCode.PROFIT_SOURCE_UNAVAILABLE);
        }
        if (response == null || !response.success() || response.data() == null) {
            log.warn("손익 원천 응답 이상(재계산 중단): source={}, siteId={}", sourceName, siteId);
            throw new BusinessException(ErrorCode.PROFIT_SOURCE_UNAVAILABLE);
        }
        return response.data();
    }

    private EstimateProfitSource validEstimate(Long siteId, EstimateProfitSource source) {
        if (!siteId.equals(source.siteId()) || source.profitEvents() == null
                || source.orphanProfitEventCount() == null || source.orphanProfitEventCount() < 0
                || !validAmount(source.confirmedTotalAmount())) {
            throw invalidSource("estimate", siteId);
        }
        Set<String> seen = new HashSet<>();
        for (EstimateProfitSource.Event event : source.profitEvents()) {
            if (event == null || !validEventId(event.eventId()) || !seen.add(event.eventId())
                    || !(ProfitEventType.ESTIMATE_PARSED.name().equals(event.eventType())
                    || ProfitEventType.ESTIMATE_DELETED.name().equals(event.eventType()))) {
                throw invalidSource("estimate", siteId);
            }
        }
        requireStorable(source.confirmedTotalAmount(), siteId);
        return source;
    }

    private PurchaseProfitSource validPurchase(Long siteId, PurchaseProfitSource source) {
        if (!siteId.equals(source.siteId()) || source.purchases() == null
                || !validAmount(source.totalAmount())) {
            throw invalidSource("purchase", siteId);
        }
        Set<Long> seen = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (PurchaseProfitSource.Item item : source.purchases()) {
            if (item == null || item.purchaseId() == null || item.purchaseId() <= 0
                    || !seen.add(item.purchaseId()) || item.revision() == null || item.revision() < 1
                    || item.revision() == PurchaseProfitProjection.RECONCILED_TOMBSTONE_REVISION
                    || !validAmount(item.totalAmount()) || item.totalAmount().compareTo(MAX_AMOUNT) > 0) {
                throw invalidSource("purchase", siteId);
            }
            sum = sum.add(item.totalAmount());
        }
        if (sum.compareTo(source.totalAmount()) != 0) {
            throw invalidSource("purchase", siteId);
        }
        requireStorable(source.totalAmount(), siteId);
        return source;
    }

    private boolean validAmount(BigDecimal amount) {
        return amount != null && amount.signum() >= 0 && amount.stripTrailingZeros().scale() <= 2;
    }

    private boolean validEventId(String eventId) {
        try {
            return eventId != null && eventId.equals(UUID.fromString(eventId).toString());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** site_profits 금액 컬럼 DECIMAL(15,2) 밖이면 저장이 실패하므로 반영 전에 막는다. */
    private void requireStorable(BigDecimal amount, Long siteId) {
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            log.warn("손익 원천 합계가 저장 범위를 넘어 재계산 중단: siteId={}", siteId);
            throw new BusinessException(ErrorCode.PROFIT_RECONCILE_CONFLICT);
        }
    }

    private BusinessException invalidSource(String sourceName, Long siteId) {
        log.warn("손익 원천 응답 검증 실패(재계산 중단): source={}, siteId={}", sourceName, siteId);
        return new BusinessException(ErrorCode.PROFIT_SOURCE_UNAVAILABLE);
    }

    private List<EstimateProfitSource.Event> unprocessedEvents(List<EstimateProfitSource.Event> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        Set<String> processed = new HashSet<>();
        processedEventRepository.findAllById(events.stream().map(EstimateProfitSource.Event::eventId).toList())
                .forEach(event -> processed.add(event.getEventId()));
        return events.stream().filter(event -> !processed.contains(event.eventId())).toList();
    }

    private ProjectionPlan planProjections(Long siteId, List<PurchaseProfitSource.Item> items) {
        Map<Long, PurchaseProfitProjection> existing = new HashMap<>();
        projectionRepository.findBySiteId(siteId).forEach(p -> existing.put(p.getPurchaseId(), p));
        List<Long> sourceIds = items.stream().map(PurchaseProfitSource.Item::purchaseId).toList();
        for (PurchaseProfitProjection projection : projectionRepository.findAllById(sourceIds)) {
            if (!siteId.equals(projection.getSiteId())) {
                // 같은 매입 id가 다른 현장 projection에 있으면 원천/projection 중 하나가 손상된 것
                log.warn("매입 projection 현장 불일치(재계산 중단): siteId={}, purchaseId={}",
                        siteId, projection.getPurchaseId());
                throw new BusinessException(ErrorCode.PROFIT_RECONCILE_CONFLICT);
            }
        }

        ProjectionPlan plan = new ProjectionPlan();
        Set<Long> sourceIdSet = new HashSet<>(sourceIds);
        for (PurchaseProfitSource.Item item : items) {
            PurchaseProfitProjection projection = existing.get(item.purchaseId());
            if (projection == null || projection.isDeleted()
                    || !projection.getLastRevision().equals(item.revision())
                    || projection.getCurrentAmount().compareTo(item.totalAmount()) != 0) {
                plan.rewrites.put(item.purchaseId(), item);
            }
        }
        for (PurchaseProfitProjection projection : existing.values()) {
            if (!projection.isDeleted() && !sourceIdSet.contains(projection.getPurchaseId())) {
                plan.tombstones.add(projection);
            }
        }
        return plan;
    }

    private static final class ProjectionPlan {
        private final Map<Long, PurchaseProfitSource.Item> rewrites = new LinkedHashMap<>();
        private final List<PurchaseProfitProjection> tombstones = new ArrayList<>();

        int size() {
            return rewrites.size() + tombstones.size();
        }

        void apply(Long siteId, String reconcileId, PurchaseProfitProjectionRepository repository) {
            for (PurchaseProfitSource.Item item : rewrites.values()) {
                PurchaseProfitProjection projection = repository.findById(item.purchaseId()).orElse(null);
                if (projection == null) {
                    repository.save(new PurchaseProfitProjection(item.purchaseId(), siteId,
                            item.revision(), item.totalAmount(), false, reconcileId));
                } else {
                    projection.replace(item.revision(), item.totalAmount(), false, reconcileId);
                }
            }
            tombstones.forEach(projection -> projection.markReconciledDeleted(reconcileId));
        }
    }
}
