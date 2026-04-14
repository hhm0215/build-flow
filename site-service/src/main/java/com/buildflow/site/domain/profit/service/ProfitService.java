package com.buildflow.site.domain.profit.service;

import com.buildflow.site.domain.profit.dto.ProfitResponse;
import com.buildflow.site.domain.profit.entity.SiteProfit;
import com.buildflow.site.domain.profit.repository.SiteProfitRepository;
import com.buildflow.site.domain.site.repository.SiteRepository;
import com.buildflow.site.global.exception.BusinessException;
import com.buildflow.site.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfitService {

    private final SiteProfitRepository siteProfitRepository;
    private final SiteRepository siteRepository;

    public ProfitResponse getProfit(Long siteId) {
        if (!siteRepository.existsById(siteId)) {
            throw new BusinessException(ErrorCode.SITE_NOT_FOUND);
        }

        return siteProfitRepository.findBySiteId(siteId)
                .map(ProfitResponse::from)
                .orElse(ProfitResponse.empty(siteId));
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

    private SiteProfit getOrCreateProfit(Long siteId) {
        return siteProfitRepository.findBySiteId(siteId)
                .orElseGet(() -> siteProfitRepository.save(
                        SiteProfit.builder().siteId(siteId).build()
                ));
    }
}
