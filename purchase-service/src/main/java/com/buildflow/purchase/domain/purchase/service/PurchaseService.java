package com.buildflow.purchase.domain.purchase.service;

import com.buildflow.purchase.domain.purchase.dto.PurchaseCreateRequest;
import com.buildflow.purchase.domain.purchase.dto.PurchaseResponse;
import com.buildflow.purchase.domain.purchase.dto.PurchaseSiteAssignRequest;
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

    /**
     * siteId가 없으면 현장 미배정 매입으로 저장한다. 미배정 매입은 어느 현장 손익에도 속하지 않으므로
     * site-service 조회·손익 outbox 이벤트를 모두 생략하고, 배정 시점에 purchase.registered를 처음 발행한다.
     */
    @Transactional
    public PurchaseResponse create(PurchaseCreateRequest request) {
        boolean unassigned = request.getSiteId() == null;
        if (!unassigned) {
            siteReferenceValidator.requireExistingSite(request.getSiteId());
        }
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
        if (unassigned) {
            return PurchaseResponse.from(saved);
        }

        enqueueRegistered(saved);
        purchaseRepository.flush();
        // 첫 검증 후 저장 사이에 현장이 보관됐으면 매입·outbox 이벤트를 함께 롤백한다
        siteReferenceValidator.recheckBeforeCommit(saved.getSiteId());

        return PurchaseResponse.from(saved);
    }

    /**
     * 미배정 매입에 현장을 배정한다. 이 매입에 대한 손익 이벤트는 이전에 한 번도 발행되지 않았으므로
     * site-service projection 입장에서는 신규 매입이다 → purchase.registered(증가한 revision)로 발행한다.
     * 같은 현장 재요청은 변경 없이 성공(멱등), 다른 현장으로의 이동은 409.
     */
    @Transactional
    public PurchaseResponse assignSite(Long id, PurchaseSiteAssignRequest request) {
        Long siteId = request.getSiteId();
        Purchase purchase = getPurchaseForUpdate(id);
        if (!purchase.isUnassigned()) {
            if (purchase.getSiteId().equals(siteId)) {
                return PurchaseResponse.from(purchase);
            }
            throw new BusinessException(ErrorCode.PURCHASE_SITE_ALREADY_ASSIGNED);
        }
        siteReferenceValidator.requireExistingSite(siteId);
        purchase.assignSite(siteId);

        enqueueRegistered(purchase);
        purchaseRepository.flush();
        // 첫 검증 직후 현장이 보관·삭제됐으면 배정·outbox 이벤트를 함께 롤백한다
        siteReferenceValidator.recheckBeforeCommit(siteId);

        return PurchaseResponse.from(purchase);
    }

    public List<PurchaseResponse> findAll(Long siteId, boolean unassignedOnly) {
        if (unassignedOnly && siteId != null) {
            throw new BusinessException(ErrorCode.INVALID_PURCHASE_FILTER);
        }
        List<Purchase> purchases;
        if (unassignedOnly) {
            purchases = purchaseRepository.findBySiteIdIsNullOrderByCreatedAtDesc();
        } else if (siteId != null) {
            purchases = purchaseRepository.findBySiteIdOrderByCreatedAtDesc(siteId);
        } else {
            purchases = purchaseRepository.findAllByOrderByCreatedAtDesc();
        }

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
        if (purchase.isUnassigned()) {
            // 손익에 반영된 적이 없으므로 이벤트 없이 저장만 한다(revision은 증가해 배정 시 단조성 유지)
            return PurchaseResponse.from(purchase);
        }

        kafkaProducerService.sendPurchaseUpdated(
                PurchaseUpdatedPayload.builder()
                        .purchaseId(purchase.getId())
                        .siteId(purchase.getSiteId())
                        .revision(purchase.getEventRevision())
                        .oldTotalAmount(oldTotalAmount)
                        .newTotalAmount(purchase.getTotalAmount())
                        .itemName(purchase.getItemName())
                        .supplier(purchase.getSupplier())
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
        if (purchase.isUnassigned()) {
            // 손익 이벤트를 발행한 적 없는 미배정 매입은 삭제 이벤트도 만들지 않는다
            purchaseRepository.delete(purchase);
            return;
        }
        siteReferenceValidator.requireSiteNotArchived(purchase.getSiteId());
        long deleteRevision = purchase.incrementEventRevision();

        kafkaProducerService.sendPurchaseDeleted(
                PurchaseDeletedPayload.builder()
                        .purchaseId(purchase.getId())
                        .siteId(purchase.getSiteId())
                        .revision(deleteRevision)
                        .totalAmount(purchase.getTotalAmount())
                        .itemName(purchase.getItemName())
                        .supplier(purchase.getSupplier())
                        .build()
        );

        purchaseRepository.delete(purchase);
        purchaseRepository.flush();
        siteReferenceValidator.recheckNotArchivedBeforeCommit(purchase.getSiteId());
    }

    private void enqueueRegistered(Purchase purchase) {
        kafkaProducerService.sendPurchaseRegistered(
                PurchaseRegisteredPayload.builder()
                        .purchaseId(purchase.getId())
                        .siteId(purchase.getSiteId())
                        .revision(purchase.getEventRevision())
                        .totalAmount(purchase.getTotalAmount())
                        .itemName(purchase.getItemName())
                        .supplier(purchase.getSupplier())
                        .build()
        );
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
