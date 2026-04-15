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
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "tax_invoices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class TaxInvoice {

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
        this.siteId = siteId;
        this.type = type;
        this.supplyAmount = supplyAmount;
        this.taxAmount = taxAmount;
        this.totalAmount = supplyAmount.add(taxAmount);
        this.counterparty = counterparty;
        this.issueDate = issueDate;
        this.memo = memo;
    }

    public void update(TaxInvoiceType type, BigDecimal supplyAmount, BigDecimal taxAmount,
                       String counterparty, LocalDate issueDate, String memo) {
        this.type = type;
        this.supplyAmount = supplyAmount;
        this.taxAmount = taxAmount;
        this.totalAmount = supplyAmount.add(taxAmount);
        this.counterparty = counterparty;
        this.issueDate = issueDate;
        this.memo = memo;
    }

    public void confirmPayment(LocalDate paymentDate) {
        if (this.paymentConfirmed) {
            throw new BusinessException(ErrorCode.ALREADY_PAYMENT_CONFIRMED);
        }
        this.paymentConfirmed = true;
        this.paymentDate = paymentDate;
    }
}
