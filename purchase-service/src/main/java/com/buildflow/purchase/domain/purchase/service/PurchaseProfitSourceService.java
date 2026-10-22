package com.buildflow.purchase.domain.purchase.service;

import com.buildflow.purchase.domain.purchase.dto.PurchaseProfitSourceResponse;
import com.buildflow.purchase.domain.purchase.entity.Purchase;
import com.buildflow.purchase.domain.purchase.repository.PurchaseRepository;
import com.buildflow.purchase.global.exception.BusinessException;
import com.buildflow.purchase.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/** 손익 재계산 원천: 현장의 현재 매입과 revision. 매입 금액·revision은 같은 트랜잭션에서 함께 바뀐다. */
@Service
@RequiredArgsConstructor
public class PurchaseProfitSourceService {

    private final PurchaseRepository purchaseRepository;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PurchaseProfitSourceResponse snapshot(Long siteId) {
        if (siteId == null || siteId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_SITE_REFERENCE);
        }
        List<Purchase> purchases = purchaseRepository.findBySiteIdOrderByIdAsc(siteId);
        BigDecimal total = purchases.stream()
                .map(Purchase::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return PurchaseProfitSourceResponse.builder()
                .siteId(siteId)
                .totalAmount(total)
                .purchases(purchases.stream()
                        .map(purchase -> PurchaseProfitSourceResponse.Item.builder()
                                .purchaseId(purchase.getId())
                                .revision(purchase.getEventRevision())
                                .totalAmount(purchase.getTotalAmount())
                                .build())
                        .toList())
                .build();
    }
}
