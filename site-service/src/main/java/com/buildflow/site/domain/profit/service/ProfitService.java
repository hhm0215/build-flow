package com.buildflow.site.domain.profit.service;

import com.buildflow.site.domain.profit.dto.ProfitResponse;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfitService {

    private final SiteProfitRepository siteProfitRepository;
    private final ProcessedProfitEventRepository processedEventRepository;
    private final PurchaseProfitProjectionRepository purchaseProjectionRepository;
    private final SiteRepository siteRepository;

    public ProfitResponse getProfit(Long siteId) {
        if (!siteRepository.existsById(siteId)) {
            throw new BusinessException(ErrorCode.SITE_NOT_FOUND);
        }

        return siteProfitRepository.findBySiteId(siteId)
                .map(ProfitResponse::from)
                .orElse(ProfitResponse.empty(siteId));
    }

    /**
     * The site row lock serializes events for one site, including the first profit-row insert.
     * The processed-event row and profit change commit or roll back together.
     */
    @Transactional
    public boolean applyEvent(String eventId, ProfitEventType eventType, Long siteId,
                              BigDecimal amount, BigDecimal previousAmount) {
        validateEvent(eventId, eventType, siteId, amount, previousAmount);
        lockSite(siteId);
        if (processedEventRepository.existsById(eventId)) {
            log.info("이미 반영한 손익 이벤트 생략: eventId={}", eventId);
            return false;
        }

        processedEventRepository.saveAndFlush(
                new ProcessedProfitEvent(eventId, siteId, eventType.name()));

        SiteProfit profit = loadOrCreateProfit(siteId);
        switch (eventType) {
            case ESTIMATE_PARSED -> profit.addEstimateAmount(amount);
            case ESTIMATE_DELETED -> profit.subtractEstimateAmount(amount);
            default -> throw new IllegalArgumentException("매입 이벤트는 projection 경로로 처리해야 합니다.");
        }
        log.info("손익 이벤트 반영: eventId={}, type={}, siteId={}", eventId, eventType, siteId);
        return true;
    }

    /**
     * Applies a full purchase state snapshot. Revision gaps are allowed because events from
     * different topics can arrive out of order; only the highest revision contributes to profit.
     */
    @Transactional
    public boolean applyPurchaseEvent(String eventId, ProfitEventType eventType, Long purchaseId,
                                      Long siteId, long revision, BigDecimal currentAmount) {
        validatePurchaseEvent(eventId, eventType, purchaseId, siteId, revision, currentAmount);
        lockSite(siteId);
        if (processedEventRepository.existsById(eventId)) {
            log.info("이미 반영한 매입 손익 이벤트 생략: eventId={}", eventId);
            return false;
        }

        PurchaseProfitProjection projection = purchaseProjectionRepository.findById(purchaseId)
                .orElse(null);
        if (projection != null && !projection.getSiteId().equals(siteId)) {
            throw new IllegalArgumentException("같은 매입의 siteId가 변경되었습니다.");
        }

        processedEventRepository.saveAndFlush(
                new ProcessedProfitEvent(eventId, siteId, eventType.name()));

        boolean incomingDeleted = eventType == ProfitEventType.PURCHASE_DELETED;
        if (projection != null) {
            int revisionOrder = Long.compare(revision, projection.getLastRevision());
            if (revisionOrder < 0) {
                log.info("지연 도착한 매입 이벤트 생략: eventId={}, purchaseId={}, revision={}, latest={}",
                        eventId, purchaseId, revision, projection.getLastRevision());
                return false;
            }
            if (revisionOrder == 0) {
                if (!projection.hasSameState(siteId, currentAmount, incomingDeleted)) {
                    throw new IllegalArgumentException("같은 매입 revision의 상태가 충돌합니다.");
                }
                log.info("이미 반영한 매입 revision 생략: eventId={}, purchaseId={}, revision={}",
                        eventId, purchaseId, revision);
                return false;
            }
            if (projection.isDeleted() && !incomingDeleted) {
                throw new IllegalArgumentException("삭제된 매입은 다시 활성화할 수 없습니다.");
            }
        }

        BigDecimal previousContribution = projection == null
                ? BigDecimal.ZERO : projection.contribution();
        BigDecimal nextContribution = incomingDeleted ? BigDecimal.ZERO : currentAmount;
        BigDecimal delta = nextContribution.subtract(previousContribution);

        if (projection == null) {
            projection = new PurchaseProfitProjection(
                    purchaseId, siteId, revision, currentAmount, incomingDeleted, eventId);
            purchaseProjectionRepository.save(projection);
        } else {
            projection.replace(revision, currentAmount, incomingDeleted, eventId);
        }

        if (delta.signum() != 0) {
            loadOrCreateProfit(siteId).adjustPurchaseAmount(delta);
        }
        log.info("매입 projection 반영: eventId={}, purchaseId={}, revision={}, delta={}, deleted={}",
                eventId, purchaseId, revision, delta, incomingDeleted);
        return true;
    }

    private void validateEvent(String eventId, ProfitEventType eventType, Long siteId,
                               BigDecimal amount, BigDecimal previousAmount) {
        if (eventId == null || !eventId.equals(UUID.fromString(eventId).toString())
                || (eventType != ProfitEventType.ESTIMATE_PARSED
                && eventType != ProfitEventType.ESTIMATE_DELETED)
                || siteId == null || siteId <= 0
                || amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("잘못된 손익 이벤트");
        }
    }

    private void validatePurchaseEvent(String eventId, ProfitEventType eventType, Long purchaseId,
                                       Long siteId, long revision, BigDecimal currentAmount) {
        boolean purchaseType = eventType == ProfitEventType.PURCHASE_REGISTERED
                || eventType == ProfitEventType.PURCHASE_UPDATED
                || eventType == ProfitEventType.PURCHASE_DELETED;
        boolean validTypeRevision = eventType == ProfitEventType.PURCHASE_REGISTERED
                ? revision == 1L : revision >= 2L;
        if (eventId == null || !eventId.equals(UUID.fromString(eventId).toString())
                || !purchaseType || !validTypeRevision
                || purchaseId == null || purchaseId <= 0
                || siteId == null || siteId <= 0
                || currentAmount == null || currentAmount.signum() < 0) {
            throw new IllegalArgumentException("잘못된 매입 손익 이벤트");
        }
    }

    @Transactional
    public void addEstimateAmount(Long siteId, BigDecimal amount) {
        SiteProfit profit = getOrCreateProfit(siteId);
        profit.addEstimateAmount(amount);
        log.info("손익 갱신 (견적): siteId={}, 추가금액={}, 총견적={}", siteId, amount, profit.getTotalEstimateAmount());
    }

    @Transactional
    public void subtractEstimateAmount(Long siteId, BigDecimal amount) {
        SiteProfit profit = getOrCreateProfit(siteId);
        profit.subtractEstimateAmount(amount);
        log.info("손익 갱신 (견적 삭제): siteId={}, 차감금액={}, 총견적={}", siteId, amount, profit.getTotalEstimateAmount());
    }

    private SiteProfit getOrCreateProfit(Long siteId) {
        lockSite(siteId);
        return loadOrCreateProfit(siteId);
    }

    private void lockSite(Long siteId) {
        siteRepository.findByIdForUpdate(siteId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SITE_NOT_FOUND));
    }

    private SiteProfit loadOrCreateProfit(Long siteId) {
        return siteProfitRepository.findBySiteId(siteId)
                .orElseGet(() -> siteProfitRepository.save(
                        SiteProfit.builder().siteId(siteId).build()
                ));
    }
}
