package com.buildflow.tax.domain.taxinvoice.entity;

import com.buildflow.tax.global.exception.BusinessException;
import com.buildflow.tax.global.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "tax_invoices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class TaxInvoice {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999.99");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long siteId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaxInvoiceType type;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal supplyAmount;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal taxAmount;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount;

    @Column(length = 200)
    private String counterparty;

    private LocalDate issueDate;

    @Column(nullable = false)
    private boolean paymentConfirmed = false;

    private LocalDate paymentDate;

    @Column(columnDefinition = "TEXT")
    private String memo;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    private TaxInvoice(Long siteId, TaxInvoiceType type, BigDecimal supplyAmount,
                       BigDecimal taxAmount, String counterparty,
                       LocalDate issueDate, String memo) {
        Amounts amounts = validateAmounts(supplyAmount, taxAmount);
        this.siteId = siteId;
        this.type = type;
        this.supplyAmount = amounts.supplyAmount();
        this.taxAmount = amounts.taxAmount();
        this.totalAmount = amounts.totalAmount();
        this.counterparty = counterparty;
        this.issueDate = issueDate;
        this.memo = memo;
    }

    public void update(TaxInvoiceType type, BigDecimal supplyAmount, BigDecimal taxAmount,
                       String counterparty, LocalDate issueDate, String memo) {
        validateMutable();
        Amounts amounts = validateAmounts(supplyAmount, taxAmount);
        this.type = type;
        this.supplyAmount = amounts.supplyAmount();
        this.taxAmount = amounts.taxAmount();
        this.totalAmount = amounts.totalAmount();
        this.counterparty = counterparty;
        this.issueDate = issueDate;
        this.memo = memo;
    }

    public void validateMutable() {
        if (this.paymentConfirmed) {
            throw new BusinessException(ErrorCode.PAYMENT_CONFIRMED_TAX_INVOICE_IMMUTABLE);
        }
    }

    public void confirmPayment(LocalDate paymentDate) {
        if (this.type != TaxInvoiceType.SALES) {
            throw new BusinessException(ErrorCode.PURCHASE_TAX_INVOICE_PAYMENT_NOT_ALLOWED);
        }
        if (this.paymentConfirmed) {
            throw new BusinessException(ErrorCode.ALREADY_PAYMENT_CONFIRMED);
        }
        this.paymentConfirmed = true;
        this.paymentDate = paymentDate;
    }

    private static Amounts validateAmounts(BigDecimal supplyAmount, BigDecimal taxAmount) {
        BigDecimal normalizedSupply = normalizeAmount(supplyAmount);
        BigDecimal normalizedTax = normalizeAmount(taxAmount);
        BigDecimal total = normalizedSupply.add(normalizedTax);
        if (total.compareTo(MAX_AMOUNT) > 0) {
            throw new BusinessException(ErrorCode.INVALID_TAX_INVOICE_AMOUNT);
        }
        return new Amounts(normalizedSupply, normalizedTax, total);
    }

    private static BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null || amount.signum() < 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new BusinessException(ErrorCode.INVALID_TAX_INVOICE_AMOUNT);
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ignored) {
            throw new BusinessException(ErrorCode.INVALID_TAX_INVOICE_AMOUNT);
        }
    }

    private record Amounts(BigDecimal supplyAmount, BigDecimal taxAmount, BigDecimal totalAmount) {
    }
}
