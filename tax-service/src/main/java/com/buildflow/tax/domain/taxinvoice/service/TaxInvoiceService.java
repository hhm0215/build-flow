package com.buildflow.tax.domain.taxinvoice.service;

import com.buildflow.tax.domain.taxinvoice.dto.*;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoice;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoiceType;
import com.buildflow.tax.domain.taxinvoice.repository.TaxInvoiceRepository;
import com.buildflow.tax.global.exception.BusinessException;
import com.buildflow.tax.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaxInvoiceService {

    private final TaxInvoiceRepository taxInvoiceRepository;

    @Transactional
    public TaxInvoiceResponse create(TaxInvoiceCreateRequest request) {
        TaxInvoice taxInvoice = TaxInvoice.builder()
                .siteId(request.getSiteId())
                .type(request.getType())
                .supplyAmount(request.getSupplyAmount())
                .taxAmount(request.getTaxAmount())
                .counterparty(request.getCounterparty())
                .issueDate(request.getIssueDate())
                .memo(request.getMemo())
                .build();

        return TaxInvoiceResponse.from(taxInvoiceRepository.save(taxInvoice));
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

        return invoices.stream()
                .map(TaxInvoiceResponse::from)
                .toList();
    }

    public TaxInvoiceResponse findById(Long id) {
        return TaxInvoiceResponse.from(getTaxInvoice(id));
    }

    @Transactional
    public TaxInvoiceResponse update(Long id, TaxInvoiceUpdateRequest request) {
        TaxInvoice taxInvoice = getTaxInvoice(id);
        taxInvoice.update(
                request.getType(),
                request.getSupplyAmount(),
                request.getTaxAmount(),
                request.getCounterparty(),
                request.getIssueDate(),
                request.getMemo()
        );
        return TaxInvoiceResponse.from(taxInvoice);
    }

    @Transactional
    public TaxInvoiceResponse confirmPayment(Long id, PaymentConfirmRequest request) {
        TaxInvoice taxInvoice = getTaxInvoice(id);
        LocalDate paymentDate = (request.getPaymentDate() != null)
                ? request.getPaymentDate()
                : LocalDate.now();
        taxInvoice.confirmPayment(paymentDate);
        return TaxInvoiceResponse.from(taxInvoice);
    }

    @Transactional
    public void delete(Long id) {
        TaxInvoice taxInvoice = getTaxInvoice(id);
        taxInvoiceRepository.delete(taxInvoice);
    }

    public OutstandingResponse getOutstanding(Long siteId) {
        List<TaxInvoice> allSales = taxInvoiceRepository
                .findBySiteIdAndTypeOrderByCreatedAtDesc(siteId, TaxInvoiceType.SALES);

        BigDecimal totalSalesAmount = allSales.stream()
                .map(TaxInvoice::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal confirmedAmount = allSales.stream()
                .filter(TaxInvoice::isPaymentConfirmed)
                .map(TaxInvoice::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<TaxInvoice> unpaid = taxInvoiceRepository
                .findBySiteIdAndTypeAndPaymentConfirmedFalseOrderByIssueDateAsc(
                        siteId, TaxInvoiceType.SALES);

        return OutstandingResponse.builder()
                .siteId(siteId)
                .totalSalesAmount(totalSalesAmount)
                .confirmedAmount(confirmedAmount)
                .outstandingAmount(totalSalesAmount.subtract(confirmedAmount))
                .unpaidCount(unpaid.size())
                .unpaidInvoices(unpaid.stream().map(TaxInvoiceResponse::from).toList())
                .build();
    }

    private TaxInvoice getTaxInvoice(Long id) {
        return taxInvoiceRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TAX_INVOICE_NOT_FOUND));
    }
}
