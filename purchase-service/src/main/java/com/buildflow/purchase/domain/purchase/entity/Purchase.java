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
@Table(name = "purchases")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Purchase {

    private static final BigDecimal MAX_UNIT_PRICE = new BigDecimal("9999999999.99");
    private static final BigDecimal MAX_TOTAL_AMOUNT = new BigDecimal("9999999999999.99");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
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
