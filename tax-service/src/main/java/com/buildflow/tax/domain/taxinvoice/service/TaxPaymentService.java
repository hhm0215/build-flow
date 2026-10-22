package com.buildflow.tax.domain.taxinvoice.service;

import com.buildflow.tax.domain.taxinvoice.dto.TaxPaymentCancelRequest;
import com.buildflow.tax.domain.taxinvoice.dto.TaxPaymentCreateRequest;
import com.buildflow.tax.domain.taxinvoice.dto.TaxPaymentResponse;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoice;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoiceType;
import com.buildflow.tax.domain.taxinvoice.entity.TaxPayment;
import com.buildflow.tax.domain.taxinvoice.event.TaxInvoiceRegisteredPayload;
import com.buildflow.tax.domain.taxinvoice.repository.TaxInvoiceRepository;
import com.buildflow.tax.domain.taxinvoice.repository.TaxPaymentRepository;
import com.buildflow.tax.global.exception.BusinessException;
import com.buildflow.tax.global.exception.ErrorCode;
import com.buildflow.tax.global.kafka.KafkaProducerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 매출 세금계산서 입금 내역(분할 입금·취소). 모든 변경은 계산서 행 비관적 잠금 아래에서
 * 유효 입금 합계를 다시 읽어 초과 입금과 완료 상태 경합을 막는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaxPaymentService {

    static final String LEGACY_MEMO = "기존 입금 확인 이관";
    static final String FULL_PAYMENT_MEMO = "잔액 전액 입금 확인";

    private final TaxInvoiceRepository taxInvoiceRepository;
    private final TaxPaymentRepository taxPaymentRepository;
    private final KafkaProducerService kafkaProducerService;
    private final SiteReferenceValidator siteReferenceValidator;

    public List<TaxPaymentResponse> findPayments(Long taxInvoiceId) {
        TaxInvoice invoice = taxInvoiceRepository.findById(taxInvoiceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TAX_INVOICE_NOT_FOUND));
        return taxPaymentRepository.findByTaxInvoiceIdOrderByPaymentDateAscIdAsc(invoice.getId()).stream()
                .map(TaxPaymentResponse::from)
                .toList();
    }

    @Transactional
    public TaxPaymentResponse record(Long taxInvoiceId, TaxPaymentCreateRequest request) {
        TaxInvoice invoice = lock(taxInvoiceId);
        invoice.requireSales();
        // 보관 현장도 늦게 들어온 입금 기록은 허용한다(ADR-021 2차 규칙과 동일).
        ensureLegacyLedger(invoice);
        if (request.getPaymentDate() == null) {
            throw new BusinessException(ErrorCode.INVALID_TAX_PAYMENT_DATE);
        }
        BigDecimal amount = TaxPayment.normalizeAmount(request.getAmount());
        BigDecimal remaining = invoice.getTotalAmount().subtract(taxPaymentRepository.sumActiveAmount(invoice.getId()));
        if (amount.compareTo(remaining) > 0) {
            throw new BusinessException(ErrorCode.TAX_PAYMENT_EXCEEDS_BALANCE);
        }
        TaxPayment saved = taxPaymentRepository.save(TaxPayment.builder()
                .taxInvoiceId(invoice.getId())
                .amount(amount)
                .paymentDate(request.getPaymentDate())
                .memo(request.getMemo())
                .build());
        refreshInvoiceState(invoice);
        return TaxPaymentResponse.from(saved);
    }

    /** 하위 호환 "입금 확인": 남은 잔액 전부를 한 건으로 기록한다. */
    @Transactional
    public TaxInvoice payRemaining(Long taxInvoiceId, LocalDate paymentDate) {
        TaxInvoice invoice = lock(taxInvoiceId);
        invoice.requireSales();
        if (invoice.isPaymentConfirmed()) {
            throw new BusinessException(ErrorCode.ALREADY_PAYMENT_CONFIRMED);
        }
        BigDecimal remaining = invoice.getTotalAmount().subtract(taxPaymentRepository.sumActiveAmount(invoice.getId()));
        if (remaining.signum() <= 0) {
            throw new BusinessException(ErrorCode.NO_REMAINING_BALANCE);
        }
        taxPaymentRepository.save(TaxPayment.builder()
                .taxInvoiceId(invoice.getId())
                .amount(remaining)
                .paymentDate(paymentDate != null ? paymentDate : LocalDate.now())
                .memo(FULL_PAYMENT_MEMO)
                .build());
        refreshInvoiceState(invoice);
        return invoice;
    }

    @Transactional
    public TaxPaymentResponse cancel(Long taxInvoiceId, Long paymentId, TaxPaymentCancelRequest request) {
        TaxInvoice invoice = lock(taxInvoiceId);
        // 취소는 확정된 수금 기록의 정정이므로 보관 현장에서는 복원 후 가능(409).
        siteReferenceValidator.requireSiteNotArchived(invoice.getSiteId());
        invoice.requireSales();
        ensureLegacyLedger(invoice);
        TaxPayment payment = taxPaymentRepository.findByIdAndTaxInvoiceId(paymentId, invoice.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TAX_PAYMENT_NOT_FOUND));
        payment.cancel(LocalDateTime.now(), request == null ? null : request.getReason());
        taxPaymentRepository.flush();
        refreshInvoiceState(invoice);
        return TaxPaymentResponse.from(payment);
    }

    /** 잠긴 계산서의 유효 입금 합계(취소 제외). */
    public BigDecimal activePaidAmount(Long taxInvoiceId) {
        return taxPaymentRepository.sumActiveAmount(taxInvoiceId);
    }

    /** 응답용 유효 입금 합계. 입금 행이 없는 기존 완료 자료는 총액을 입금된 것으로 본다. */
    public BigDecimal paidAmount(TaxInvoice invoice) {
        if (invoice.getType() != TaxInvoiceType.SALES) {
            return null;
        }
        if (invoice.isPaymentConfirmed() && !taxPaymentRepository.existsByTaxInvoiceId(invoice.getId())) {
            return invoice.getTotalAmount();
        }
        return taxPaymentRepository.sumActiveAmount(invoice.getId());
    }

    public Map<Long, BigDecimal> paidAmounts(Collection<TaxInvoice> invoices) {
        List<Long> salesIds = invoices.stream()
                .filter(invoice -> invoice.getType() == TaxInvoiceType.SALES)
                .map(TaxInvoice::getId)
                .toList();
        Map<Long, BigDecimal> result = new HashMap<>();
        if (salesIds.isEmpty()) {
            return result;
        }
        Map<Long, BigDecimal> active = new HashMap<>();
        taxPaymentRepository.sumActiveAmounts(salesIds)
                .forEach(row -> active.put(row.getTaxInvoiceId(), row.getActiveAmount()));
        Set<Long> withRows = new HashSet<>(taxPaymentRepository.findInvoiceIdsWithPayments(salesIds));
        for (TaxInvoice invoice : invoices) {
            if (invoice.getType() != TaxInvoiceType.SALES) {
                continue;
            }
            BigDecimal paid = (invoice.isPaymentConfirmed() && !withRows.contains(invoice.getId()))
                    ? invoice.getTotalAmount()
                    : active.getOrDefault(invoice.getId(), BigDecimal.ZERO);
            result.put(invoice.getId(), paid);
        }
        return result;
    }

    /** 계산서 삭제 시 남은(취소된) 입금 행을 함께 지운다. 호출 전 유효 입금 0을 검증해야 한다. */
    @Transactional
    public void deleteLedger(Long taxInvoiceId) {
        taxPaymentRepository.deleteByTaxInvoiceId(taxInvoiceId);
    }

    /** 입금 내역 도입 전 "입금 확인"된 계산서에 총액 1건을 이관한다. 멱등. */
    @Transactional
    public int backfillLegacyConfirmed() {
        int migrated = 0;
        for (Long id : taxInvoiceRepository.findLegacyConfirmedIdsWithoutPayments()) {
            TaxInvoice invoice = taxInvoiceRepository.findByIdForUpdate(id).orElse(null);
            if (invoice != null && ensureLegacyLedger(invoice)) {
                migrated++;
            }
        }
        return migrated;
    }

    private boolean ensureLegacyLedger(TaxInvoice lockedInvoice) {
        if (!lockedInvoice.isPaymentConfirmed()
                || lockedInvoice.getTotalAmount().signum() <= 0
                || taxPaymentRepository.existsByTaxInvoiceId(lockedInvoice.getId())) {
            return false;
        }
        LocalDate date = lockedInvoice.getPaymentDate();
        if (date == null) {
            date = lockedInvoice.getCreatedAt() != null ? lockedInvoice.getCreatedAt().toLocalDate() : LocalDate.now();
        }
        taxPaymentRepository.save(TaxPayment.builder()
                .taxInvoiceId(lockedInvoice.getId())
                .amount(lockedInvoice.getTotalAmount())
                .paymentDate(date)
                .memo(LEGACY_MEMO)
                .build());
        return true;
    }

    private void refreshInvoiceState(TaxInvoice invoice) {
        BigDecimal paid = taxPaymentRepository.sumActiveAmount(invoice.getId());
        LocalDate latest = taxPaymentRepository.findLatestActivePaymentDate(invoice.getId()).orElse(null);
        boolean newlyCompleted = invoice.applyPaymentTotal(paid, latest);
        if (newlyCompleted) {
            // 기존 계약 유지: 전액 입금으로 완료 상태가 될 때만 1회 발행(분할 입금 건별 알림 없음).
            kafkaProducerService.sendTaxPaymentConfirmed(TaxInvoiceRegisteredPayload.builder()
                    .taxInvoiceId(invoice.getId())
                    .siteId(invoice.getSiteId())
                    .type(invoice.getType().name())
                    .totalAmount(invoice.getTotalAmount())
                    .counterparty(invoice.getCounterparty())
                    .build());
        }
    }

    private TaxInvoice lock(Long taxInvoiceId) {
        return taxInvoiceRepository.findByIdForUpdate(taxInvoiceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TAX_INVOICE_NOT_FOUND));
    }
}
