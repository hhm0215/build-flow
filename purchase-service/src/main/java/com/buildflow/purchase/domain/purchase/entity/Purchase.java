package com.buildflow.purchase.domain.purchase.entity;

import com.buildflow.purchase.global.exception.BusinessException;
import com.buildflow.purchase.global.exception.ErrorCode;
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
@Table(name = "purchases", indexes = @Index(
        name = "idx_purchases_evidence_tax_invoice", columnList = "evidence_tax_invoice_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Purchase {

    private static final BigDecimal MAX_UNIT_PRICE = new BigDecimal("9999999999.99");
    private static final BigDecimal MAX_TOTAL_AMOUNT = new BigDecimal("9999999999999.99");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 현장 ID. null이면 현장 미배정(홈택스 매입 세금계산서 일괄 적재 등) — 손익 이벤트를 발행하지 않고,
     * 화면에서 현장을 배정하는 순간 purchase.registered로 처음 손익에 반영한다(2026-11-01 사용자 결정).
     */
    @Column
    private Long siteId;

    @Column(nullable = false, length = 200)
    private String itemName;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount;

    @Column(length = 200)
    private String supplier;

    private LocalDate purchaseDate;

    @Column(columnDefinition = "TEXT")
    private String memo;

    @Column(nullable = false)
    private long eventRevision = 1L;

    /**
     * 증빙 매입 세금계산서 ID(tax-service). 원가의 단일 진실원은 매입 항목이며 계산서는 증빙으로만 연결한다.
     * 매입 1건은 계산서 최대 1건, 계산서 1건은 같은 현장 매입 0..N건과 연결된다. 손익 이벤트와 무관하다.
     */
    private Long evidenceTaxInvoiceId;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    private Purchase(Long siteId, String itemName, Integer quantity,
                     BigDecimal unitPrice, String supplier,
                     LocalDate purchaseDate, String memo) {
        BigDecimal normalizedUnitPrice = normalizeUnitPrice(quantity, unitPrice);
        BigDecimal calculatedTotal = calculateTotal(quantity, normalizedUnitPrice);
        this.siteId = siteId;
        this.itemName = itemName;
        this.quantity = quantity;
        this.unitPrice = normalizedUnitPrice;
        this.totalAmount = calculatedTotal;
        this.supplier = supplier;
        this.purchaseDate = purchaseDate;
        this.memo = memo;
        this.eventRevision = 1L;
    }

    public void update(String itemName, Integer quantity, BigDecimal unitPrice,
                       String supplier, LocalDate purchaseDate, String memo) {
        BigDecimal normalizedUnitPrice = normalizeUnitPrice(quantity, unitPrice);
        BigDecimal calculatedTotal = calculateTotal(quantity, normalizedUnitPrice);
        this.itemName = itemName;
        this.quantity = quantity;
        this.unitPrice = normalizedUnitPrice;
        this.totalAmount = calculatedTotal;
        this.supplier = supplier;
        this.purchaseDate = purchaseDate;
        this.memo = memo;
        this.eventRevision = Math.addExact(this.eventRevision, 1L);
    }

    public boolean isUnassigned() {
        return siteId == null;
    }

    /**
     * 미배정 매입에 현장을 처음 배정한다. 현장 존재·보관 검증은 서비스가 site-service 조회로 먼저 끝낸다.
     * 손익 projection이 매입 ID별 현장을 고정하므로 이미 배정된 매입의 현장 이동은 허용하지 않는다.
     */
    public long assignSite(Long siteId) {
        if (this.siteId != null) {
            throw new BusinessException(ErrorCode.PURCHASE_SITE_ALREADY_ASSIGNED);
        }
        this.siteId = siteId;
        return incrementEventRevision();
    }

    /** 연결 대상 검증(존재·매입 유형·같은 현장)은 서비스가 tax-service 조회로 먼저 끝낸다. */
    public void linkEvidence(Long taxInvoiceId) {
        this.evidenceTaxInvoiceId = taxInvoiceId;
    }

    public void unlinkEvidence() {
        this.evidenceTaxInvoiceId = null;
    }

    public long incrementEventRevision() {
        this.eventRevision = Math.addExact(this.eventRevision, 1L);
        return this.eventRevision;
    }

    private static BigDecimal normalizeUnitPrice(Integer quantity, BigDecimal unitPrice) {
        if (quantity == null || quantity < 1 || unitPrice == null
                || unitPrice.signum() < 0 || unitPrice.compareTo(MAX_UNIT_PRICE) > 0) {
            throw new BusinessException(ErrorCode.INVALID_PURCHASE_AMOUNT);
        }
        try {
            return unitPrice.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ignored) {
            throw new BusinessException(ErrorCode.INVALID_PURCHASE_AMOUNT);
        }
    }

    private static BigDecimal calculateTotal(Integer quantity, BigDecimal unitPrice) {
        BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(quantity));
        if (total.compareTo(MAX_TOTAL_AMOUNT) > 0) {
            throw new BusinessException(ErrorCode.INVALID_PURCHASE_AMOUNT);
        }
        return total;
    }
}
