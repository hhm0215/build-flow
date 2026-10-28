package com.buildflow.purchase.domain.purchase.service;

import com.buildflow.purchase.domain.purchase.dto.PurchaseCreateRequest;
import com.buildflow.purchase.domain.purchase.dto.PurchaseResponse;
import com.buildflow.purchase.domain.purchase.dto.PurchaseUpdateRequest;
import com.buildflow.purchase.domain.purchase.entity.Purchase;
import com.buildflow.purchase.domain.purchase.event.PurchaseDeletedPayload;
import com.buildflow.purchase.domain.purchase.event.PurchaseRegisteredPayload;
import com.buildflow.purchase.domain.purchase.event.PurchaseUpdatedPayload;
import com.buildflow.purchase.domain.purchase.repository.PurchaseRepository;
import com.buildflow.purchase.global.exception.BusinessException;
import com.buildflow.purchase.global.exception.ErrorCode;
import com.buildflow.purchase.global.kafka.KafkaProducerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchaseService {

    private final PurchaseRepository purchaseRepository;
    private final KafkaProducerService kafkaProducerService;
    private final SiteReferenceValidator siteReferenceValidator;

    @Transactional
    public PurchaseResponse create(PurchaseCreateRequest request) {
        siteReferenceValidator.requireExistingSite(request.getSiteId());
        Purchase purchase = Purchase.builder()
                .siteId(request.getSiteId())
                .itemName(request.getItemName())
                .quantity(request.getQuantity())
                .unitPrice(request.getUnitPrice())
                .supplier(request.getSupplier())
                .purchaseDate(request.getPurchaseDate())
                .memo(request.getMemo())
                .build();

        Purchase saved = purchaseRepository.save(purchase);

        kafkaProducerService.sendPurchaseRegistered(
                PurchaseRegisteredPayload.builder()
                        .purchaseId(saved.getId())
                        .siteId(saved.getSiteId())
                        .revision(saved.getEventRevision())
                        .totalAmount(saved.getTotalAmount())
                        .build()
        );
        purchaseRepository.flush();
        // 첫 검증 후 저장 사이에 현장이 보관됐으면 매입·outbox 이벤트를 함께 롤백한다
        siteReferenceValidator.recheckBeforeCommit(saved.getSiteId());

        return PurchaseResponse.from(saved);
    }

    public List<PurchaseResponse> findAll(Long siteId) {
        List<Purchase> purchases = (siteId != null)
                ? purchaseRepository.findBySiteIdOrderByCreatedAtDesc(siteId)
                : purchaseRepository.findAllByOrderByCreatedAtDesc();

        return purchases.stream()
                .map(PurchaseResponse::from)
                .toList();
    }

    public PurchaseResponse findById(Long id) {
        return PurchaseResponse.from(getPurchase(id));
    }

    @Transactional
    public PurchaseResponse update(Long id, PurchaseUpdateRequest request) {
        Purchase purchase = getPurchaseForUpdate(id);
        siteReferenceValidator.requireSiteNotArchived(purchase.getSiteId());
        BigDecimal oldTotalAmount = purchase.getTotalAmount();

        purchase.update(
                request.getItemName(),
                request.getQuantity(),
                request.getUnitPrice(),
                request.getSupplier(),
                request.getPurchaseDate(),
                request.getMemo()
        );

        kafkaProducerService.sendPurchaseUpdated(
                PurchaseUpdatedPayload.builder()
                        .purchaseId(purchase.getId())
                        .siteId(purchase.getSiteId())
                        .revision(purchase.getEventRevision())
                        .oldTotalAmount(oldTotalAmount)
                        .newTotalAmount(purchase.getTotalAmount())
                        .build()
        );
        purchaseRepository.flush();
        // 첫 검사 직후 현장이 보관됐으면 수정·outbox 이벤트를 커밋 전에 롤백한다 (ADR-021 보관 현장 문서 잠금)
        siteReferenceValidator.recheckNotArchivedBeforeCommit(purchase.getSiteId());

        return PurchaseResponse.from(purchase);
    }

    @Transactional
    public void delete(Long id) {
        Purchase purchase = getPurchaseForUpdate(id);
        siteReferenceValidator.requireSiteNotArchived(purchase.getSiteId());
        long deleteRevision = purchase.incrementEventRevision();

        kafkaProducerService.sendPurchaseDeleted(
                PurchaseDeletedPayload.builder()
                        .purchaseId(purchase.getId())
                        .siteId(purchase.getSiteId())
                        .revision(deleteRevision)
                        .totalAmount(purchase.getTotalAmount())
                        .build()
        );

        purchaseRepository.delete(purchase);
        purchaseRepository.flush();
        siteReferenceValidator.recheckNotArchivedBeforeCommit(purchase.getSiteId());
    }

    private Purchase getPurchase(Long id) {
        return purchaseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_NOT_FOUND));
    }

    private Purchase getPurchaseForUpdate(Long id) {
        return purchaseRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_NOT_FOUND));
    }
}
