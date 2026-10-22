package com.buildflow.tax.domain.taxinvoice.entity;

import com.buildflow.tax.global.exception.BusinessException;
import com.buildflow.tax.global.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 매출 세금계산서의 입금 1건. 오입금은 삭제하지 않고 취소 시각을 남겨 합계에서만 제외한다.
 */
@Entity
@Table(name = "tax_payments", indexes = {
        @Index(name = "idx_tax_payments_invoice", columnList = "tax_invoice_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class TaxPayment {

    public static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999.99");
    public static final int MEMO_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tax_invoice_id", nullable = false)
    private Long taxInvoiceId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private LocalDate paymentDate;

    @Column(length = MEMO_MAX_LENGTH)
    private String memo;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime canceledAt;

    @Column(length = MEMO_MAX_LENGTH)
    private String cancelReason;

    @Builder
    private TaxPayment(Long taxInvoiceId, BigDecimal amount, LocalDate paymentDate, String memo) {
        if (taxInvoiceId == null) {
            throw new BusinessException(ErrorCode.TAX_INVOICE_NOT_FOUND);
        }
        if (paymentDate == null) {
            throw new BusinessException(ErrorCode.INVALID_TAX_PAYMENT_DATE);
        }
        this.taxInvoiceId = taxInvoiceId;
        this.amount = normalizeAmount(amount);
        this.paymentDate = paymentDate;
        this.memo = normalizeText(memo);
    }

    public boolean isCanceled() {
        return canceledAt != null;
    }

    public void cancel(LocalDateTime canceledAt, String reason) {
        if (isCanceled()) {
            throw new BusinessException(ErrorCode.TAX_PAYMENT_ALREADY_CANCELED);
        }
        this.canceledAt = canceledAt;
        this.cancelReason = normalizeText(reason);
    }

    /** 0 초과, DECIMAL(15,2) 범위, 소수 2자리 이하만 반올림 없이 저장한다. */
    public static BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new BusinessException(ErrorCode.INVALID_TAX_PAYMENT_AMOUNT);
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ignored) {
            throw new BusinessException(ErrorCode.INVALID_TAX_PAYMENT_AMOUNT);
        }
    }

    private static String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > MEMO_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_TAX_PAYMENT_MEMO);
        }
        return trimmed;
    }
}
