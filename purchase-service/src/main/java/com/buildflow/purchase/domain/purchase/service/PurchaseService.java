package com.buildflow.purchase.domain.purchase.service;

import com.buildflow.purchase.domain.purchase.dto.PurchaseCreateRequest;
import com.buildflow.purchase.domain.purchase.dto.PurchaseResponse;
import com.buildflow.purchase.domain.purchase.dto.PurchaseUpdateRequest;
import com.buildflow.purchase.domain.purchase.entity.Purchase;
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

    @Transactional
    public PurchaseResponse create(PurchaseCreateRequest request) {
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
                        .totalAmount(saved.getTotalAmount())
                        .build()
        );

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
        Purchase purchase = getPurchase(id);
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
                        .oldTotalAmount(oldTotalAmount)
                        .newTotalAmount(purchase.getTotalAmount())
                        .build()
        );

        return PurchaseResponse.from(purchase);
    }

    @Transactional
    public void delete(Long id) {
        Purchase purchase = getPurchase(id);

        kafkaProducerService.sendPurchaseDeleted(
                PurchaseRegisteredPayload.builder()
                        .purchaseId(purchase.getId())
                        .siteId(purchase.getSiteId())
                        .totalAmount(purchase.getTotalAmount())
                        .build()
        );

        purchaseRepository.delete(purchase);
    }

    private Purchase getPurchase(Long id) {
        return purchaseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_NOT_FOUND));
    }
}
