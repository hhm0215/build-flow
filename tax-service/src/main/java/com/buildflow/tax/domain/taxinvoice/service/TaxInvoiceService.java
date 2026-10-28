package com.buildflow.tax.domain.taxinvoice.service;

import com.buildflow.tax.domain.taxinvoice.dto.*;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoice;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoiceType;
import com.buildflow.tax.domain.taxinvoice.event.TaxInvoiceRegisteredPayload;
import com.buildflow.tax.domain.taxinvoice.repository.TaxInvoiceRepository;
import com.buildflow.tax.global.exception.BusinessException;
import com.buildflow.tax.global.exception.ErrorCode;
import com.buildflow.tax.global.kafka.KafkaProducerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaxInvoiceService {

    private final TaxInvoiceRepository taxInvoiceRepository;
    private final KafkaProducerService kafkaProducerService;
    private final SiteReferenceValidator siteReferenceValidator;
    private final TaxPaymentService taxPaymentService;
    private final PurchaseEvidenceGuard purchaseEvidenceGuard;

    @Transactional
    public TaxInvoiceResponse create(TaxInvoiceCreateRequest request) {
        siteReferenceValidator.requireExistingSite(request.getSiteId());
        TaxInvoice taxInvoice = TaxInvoice.builder()
                .siteId(request.getSiteId())
                .type(request.getType())
                .supplyAmount(request.getSupplyAmount())
                .taxAmount(request.getTaxAmount())
                .counterparty(request.getCounterparty())
                .issueDate(request.getIssueDate())
                .memo(request.getMemo())
                .build();

        TaxInvoice saved = taxInvoiceRepository.save(taxInvoice);

        kafkaProducerService.sendTaxRegistered(
                TaxInvoiceRegisteredPayload.builder()
                        .taxInvoiceId(saved.getId())
                        .siteId(saved.getSiteId())
                        .type(saved.getType().name())
                        .totalAmount(saved.getTotalAmount())
                        .counterparty(saved.getCounterparty())
                        .build()
        );
        taxInvoiceRepository.flush();
        // 첫 검증 후 저장 사이에 현장이 보관됐으면 세금계산서·outbox 이벤트를 함께 롤백한다
        siteReferenceValidator.recheckBeforeCommit(saved.getSiteId());

        return TaxInvoiceResponse.from(saved, BigDecimal.ZERO);
    }

    public List<TaxInvoiceResponse> findAll(Long siteId, TaxInvoiceType type) {
        List<TaxInvoice> invoices;

        if (siteId != null && type != null) {
            invoices = taxInvoiceRepository.findBySiteIdAndTypeOrderByCreatedAtDesc(siteId, type);
        } else if (siteId != null) {
            invoices = taxInvoiceRepository.findBySiteIdOrderByCreatedAtDesc(siteId);
        } else {
            invoices = taxInvoiceRepository.findAllByOrderByCreatedAtDesc();
        }

        Map<Long, BigDecimal> paid = taxPaymentService.paidAmounts(invoices);
        return invoices.stream()
                .map(invoice -> TaxInvoiceResponse.from(invoice, paid.get(invoice.getId())))
                .toList();
    }

    public TaxInvoiceResponse findById(Long id) {
        TaxInvoice invoice = getTaxInvoice(id);
        return TaxInvoiceResponse.from(invoice, taxPaymentService.paidAmount(invoice));
    }

    @Transactional
    public TaxInvoiceResponse update(Long id, TaxInvoiceUpdateRequest request) {
        TaxInvoice taxInvoice = getTaxInvoiceForUpdate(id);
        siteReferenceValidator.requireSiteNotArchived(taxInvoice.getSiteId());
        taxInvoice.validateMutable(taxPaymentService.activePaidAmount(taxInvoice.getId()));
        if (taxInvoice.getType() == TaxInvoiceType.PURCHASE && request.getType() != TaxInvoiceType.PURCHASE) {
            purchaseEvidenceGuard.requireNoLinkedPurchases(taxInvoice.getId());
        }
        taxInvoice.update(
                request.getType(),
                request.getSupplyAmount(),
                request.getTaxAmount(),
                request.getCounterparty(),
                request.getIssueDate(),
                request.getMemo()
        );
        taxInvoiceRepository.flush();
        // 첫 검사 직후 현장이 보관됐으면 수정을 커밋 전에 롤백한다 (ADR-021 보관 현장 문서 잠금)
        siteReferenceValidator.recheckNotArchivedBeforeCommit(taxInvoice.getSiteId());
        return TaxInvoiceResponse.from(taxInvoice, BigDecimal.ZERO);
    }

    /** 하위 호환: 남은 잔액 전부를 입금 1건으로 기록하고 완료 처리한다. */
    @Transactional
    public TaxInvoiceResponse confirmPayment(Long id, PaymentConfirmRequest request) {
        TaxInvoice taxInvoice = taxPaymentService.payRemaining(id, request.getPaymentDate());
        return TaxInvoiceResponse.from(taxInvoice, taxInvoice.getTotalAmount());
    }

    @Transactional
    public void delete(Long id) {
        TaxInvoice taxInvoice = getTaxInvoiceForUpdate(id);
        siteReferenceValidator.requireSiteNotArchived(taxInvoice.getSiteId());
        taxInvoice.validateMutable(taxPaymentService.activePaidAmount(taxInvoice.getId()));
        if (taxInvoice.getType() == TaxInvoiceType.PURCHASE) {
            purchaseEvidenceGuard.requireNoLinkedPurchases(taxInvoice.getId());
        }
        taxPaymentService.deleteLedger(taxInvoice.getId());
        taxInvoiceRepository.delete(taxInvoice);
        taxInvoiceRepository.flush();
        // 입금 원장·계산서 삭제가 flush된 뒤 재확인해, 그 사이 보관됐으면 둘 다 롤백한다
        siteReferenceValidator.recheckNotArchivedBeforeCommit(taxInvoice.getSiteId());
    }

    /** 미수금 = Σ(공급가+세액) − Σ(유효 입금). 잔액이 남은 매출 계산서만 미수 건으로 센다. */
    public OutstandingResponse getOutstanding(Long siteId) {
        List<TaxInvoice> allSales = taxInvoiceRepository
                .findBySiteIdAndTypeOrderByCreatedAtDesc(siteId, TaxInvoiceType.SALES);
        Map<Long, BigDecimal> paid = taxPaymentService.paidAmounts(allSales);

        BigDecimal totalSalesAmount = BigDecimal.ZERO;
        BigDecimal confirmedAmount = BigDecimal.ZERO;
        for (TaxInvoice invoice : allSales) {
            totalSalesAmount = totalSalesAmount.add(invoice.getTotalAmount());
            confirmedAmount = confirmedAmount.add(paid.getOrDefault(invoice.getId(), BigDecimal.ZERO));
        }

        List<TaxInvoiceResponse> unpaid = allSales.stream()
                .filter(invoice -> invoice.getTotalAmount()
                        .compareTo(paid.getOrDefault(invoice.getId(), BigDecimal.ZERO)) > 0)
                .sorted(Comparator.comparing(TaxInvoice::getIssueDate,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(invoice -> TaxInvoiceResponse.from(invoice, paid.get(invoice.getId())))
                .toList();

        return OutstandingResponse.builder()
                .siteId(siteId)
                .totalSalesAmount(totalSalesAmount)
                .confirmedAmount(confirmedAmount)
                .outstandingAmount(totalSalesAmount.subtract(confirmedAmount))
                .unpaidCount(unpaid.size())
                .unpaidInvoices(unpaid)
                .build();
    }

    private TaxInvoice getTaxInvoice(Long id) {
        return taxInvoiceRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TAX_INVOICE_NOT_FOUND));
    }

    private TaxInvoice getTaxInvoiceForUpdate(Long id) {
        return taxInvoiceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TAX_INVOICE_NOT_FOUND));
    }
}
