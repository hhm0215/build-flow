package com.buildflow.purchase.domain.purchase.service;

import com.buildflow.purchase.domain.purchase.dto.EvidenceLinkCountResponse;
import com.buildflow.purchase.domain.purchase.dto.PurchaseEvidenceLinkRequest;
import com.buildflow.purchase.domain.purchase.dto.PurchaseEvidenceSummaryResponse;
import com.buildflow.purchase.domain.purchase.dto.PurchaseResponse;
import com.buildflow.purchase.domain.purchase.entity.Purchase;
import com.buildflow.purchase.domain.purchase.repository.PurchaseRepository;
import com.buildflow.purchase.global.exception.BusinessException;
import com.buildflow.purchase.global.exception.ErrorCode;
import com.buildflow.purchase.infra.feign.TaxInvoiceReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 매입 항목 ↔ 매입 세금계산서 증빙 연결. 원가는 매입 항목만 쓰므로 연결·해제는 손익 이벤트를 만들지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchaseEvidenceService {

    private final PurchaseRepository purchaseRepository;
    private final SiteReferenceValidator siteReferenceValidator;
    private final TaxInvoiceReferenceReader taxInvoiceReferenceReader;

    @Transactional
    public PurchaseResponse link(Long purchaseId, PurchaseEvidenceLinkRequest request) {
        Purchase purchase = getPurchaseForUpdate(purchaseId);
        siteReferenceValidator.requireSiteNotArchived(purchase.getSiteId());
        TaxInvoiceReference invoice = taxInvoiceReferenceReader
                .requirePurchaseInvoiceOfSite(request.getTaxInvoiceId(), purchase.getSiteId());
        purchase.linkEvidence(invoice.id());
        return PurchaseResponse.from(purchase);
    }

    @Transactional
    public PurchaseResponse unlink(Long purchaseId) {
        Purchase purchase = getPurchaseForUpdate(purchaseId);
        siteReferenceValidator.requireSiteNotArchived(purchase.getSiteId());
        purchase.unlinkEvidence();
        return PurchaseResponse.from(purchase);
    }

    public EvidenceLinkCountResponse countLinks(Long taxInvoiceId) {
        return new EvidenceLinkCountResponse(taxInvoiceId,
                purchaseRepository.countByEvidenceTaxInvoiceId(taxInvoiceId));
    }

    public PurchaseEvidenceSummaryResponse summarize(Long siteId) {
        if (siteId == null || siteId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_SITE_REFERENCE);
        }
        List<Purchase> purchases = purchaseRepository.findBySiteIdOrderByCreatedAtDesc(siteId);
        List<TaxInvoiceReference> invoices = taxInvoiceReferenceReader.findPurchaseInvoices(siteId);

        Map<Long, LinkedTotal> linkedByInvoice = new LinkedHashMap<>();
        for (TaxInvoiceReference invoice : invoices) {
            linkedByInvoice.put(invoice.id(), new LinkedTotal());
        }

        BigDecimal purchaseAmount = BigDecimal.ZERO;
        BigDecimal linkedAmount = BigDecimal.ZERO;
        int linkedCount = 0;
        int brokenCount = 0;
        for (Purchase purchase : purchases) {
            purchaseAmount = purchaseAmount.add(purchase.getTotalAmount());
            Long evidenceId = purchase.getEvidenceTaxInvoiceId();
            if (evidenceId == null) {
                continue;
            }
            LinkedTotal linked = linkedByInvoice.get(evidenceId);
            if (linked == null) {
                // 계산서가 삭제·유형 변경된 경합 잔여분. 미연결로 세고 화면에서 다시 연결하게 한다.
                brokenCount++;
                continue;
            }
            linked.add(purchase.getTotalAmount());
            linkedCount++;
            linkedAmount = linkedAmount.add(purchase.getTotalAmount());
        }

        BigDecimal supplyTotal = BigDecimal.ZERO;
        BigDecimal invoiceTotal = BigDecimal.ZERO;
        int withoutPurchase = 0;
        List<PurchaseEvidenceSummaryResponse.InvoiceEvidence> rows = new ArrayList<>();
        for (TaxInvoiceReference invoice : invoices) {
            BigDecimal supply = nonNull(invoice.supplyAmount());
            BigDecimal total = nonNull(invoice.totalAmount());
            LinkedTotal linked = linkedByInvoice.get(invoice.id());
            supplyTotal = supplyTotal.add(supply);
            invoiceTotal = invoiceTotal.add(total);
            if (linked.count == 0) {
                withoutPurchase++;
            }
            rows.add(PurchaseEvidenceSummaryResponse.InvoiceEvidence.builder()
                    .taxInvoiceId(invoice.id())
                    .counterparty(invoice.counterparty())
                    .issueDate(invoice.issueDate())
                    .supplyAmount(supply)
                    .totalAmount(total)
                    .linkedPurchaseCount(linked.count)
                    .linkedPurchaseAmount(linked.amount)
                    .supplyDifference(supply.subtract(linked.amount))
                    .totalDifference(total.subtract(linked.amount))
                    .build());
        }

        return PurchaseEvidenceSummaryResponse.builder()
                .siteId(siteId)
                .purchaseCount(purchases.size())
                .purchaseAmount(purchaseAmount)
                .linkedPurchaseCount(linkedCount)
                .linkedPurchaseAmount(linkedAmount)
                .unlinkedPurchaseCount(purchases.size() - linkedCount)
                .unlinkedPurchaseAmount(purchaseAmount.subtract(linkedAmount))
                .brokenLinkCount(brokenCount)
                .invoiceCount(invoices.size())
                .invoiceSupplyAmount(supplyTotal)
                .invoiceTotalAmount(invoiceTotal)
                .invoiceWithoutPurchaseCount(withoutPurchase)
                .supplyDifference(supplyTotal.subtract(linkedAmount))
                .totalDifference(invoiceTotal.subtract(linkedAmount))
                .invoices(rows)
                .build();
    }

    private Purchase getPurchaseForUpdate(Long id) {
        return purchaseRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_NOT_FOUND));
    }

    private static BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static final class LinkedTotal {
        private int count;
        private BigDecimal amount = BigDecimal.ZERO;

        private void add(BigDecimal value) {
            count++;
            amount = amount.add(value);
        }
    }
}
