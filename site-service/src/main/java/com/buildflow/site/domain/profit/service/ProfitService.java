package com.buildflow.site.domain.profit.service;

import com.buildflow.site.domain.profit.dto.ProfitResponse;
import com.buildflow.site.domain.profit.entity.ProcessedProfitEvent;
import com.buildflow.site.domain.profit.entity.SiteProfit;
import com.buildflow.site.domain.profit.event.ProfitEventType;
import com.buildflow.site.domain.profit.repository.ProcessedProfitEventRepository;
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
            case PURCHASE_REGISTERED -> profit.addPurchaseAmount(amount);
            case PURCHASE_UPDATED -> {
                profit.subtractPurchaseAmount(previousAmount);
                profit.addPurchaseAmount(amount);
            }
            case PURCHASE_DELETED -> profit.subtractPurchaseAmount(amount);
        }
        log.info("손익 이벤트 반영: eventId={}, type={}, siteId={}", eventId, eventType, siteId);
        return true;
    }

    private void validateEvent(String eventId, ProfitEventType eventType, Long siteId,
                               BigDecimal amount, BigDecimal previousAmount) {
        if (eventId == null || !eventId.equals(UUID.fromString(eventId).toString())
                || eventType == null || siteId == null || siteId <= 0
                || amount == null || amount.signum() < 0
                || (eventType == ProfitEventType.PURCHASE_UPDATED
                && (previousAmount == null || previousAmount.signum() < 0))) {
            throw new IllegalArgumentException("잘못된 손익 이벤트");
        }
    }

    @Transactional
    public void addEstimateAmount(Long siteId, BigDecimal amount) {
        SiteProfit profit = getOrCreateProfit(siteId);
        profit.addEstimateAmount(amount);
        log.info("손익 갱신 (견적): siteId={}, 추가금액={}, 총견적={}", siteId, amount, profit.getTotalEstimateAmount());
    }

    @Transactional
    public void addPurchaseAmount(Long siteId, BigDecimal amount) {
        SiteProfit profit = getOrCreateProfit(siteId);
        profit.addPurchaseAmount(amount);
        log.info("손익 갱신 (매입): siteId={}, 추가금액={}, 총매입={}", siteId, amount, profit.getTotalPurchaseAmount());
    }

    @Transactional
    public void subtractEstimateAmount(Long siteId, BigDecimal amount) {
        SiteProfit profit = getOrCreateProfit(siteId);
        profit.subtractEstimateAmount(amount);
        log.info("손익 갱신 (견적 삭제): siteId={}, 차감금액={}, 총견적={}", siteId, amount, profit.getTotalEstimateAmount());
    }

    @Transactional
    public void updatePurchaseAmount(Long siteId, BigDecimal oldAmount, BigDecimal newAmount) {
        SiteProfit profit = getOrCreateProfit(siteId);
        profit.subtractPurchaseAmount(oldAmount);
        profit.addPurchaseAmount(newAmount);
        log.info("손익 갱신 (매입 수정): siteId={}, 이전={}, 변경={}, 총매입={}", siteId, oldAmount, newAmount, profit.getTotalPurchaseAmount());
    }

    @Transactional
    public void subtractPurchaseAmount(Long siteId, BigDecimal amount) {
        SiteProfit profit = getOrCreateProfit(siteId);
        profit.subtractPurchaseAmount(amount);
        log.info("손익 갱신 (매입 삭제): siteId={}, 차감금액={}, 총매입={}", siteId, amount, profit.getTotalPurchaseAmount());
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
