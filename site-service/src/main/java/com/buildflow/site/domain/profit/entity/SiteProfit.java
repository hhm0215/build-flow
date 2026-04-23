package com.buildflow.site.domain.profit.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "site_profits")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class SiteProfit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long siteId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal totalEstimateAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal totalPurchaseAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal margin = BigDecimal.ZERO;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal marginRate = BigDecimal.ZERO;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    private SiteProfit(Long siteId) {
        this.siteId = siteId;
    }

    public void addEstimateAmount(BigDecimal amount) {
        this.totalEstimateAmount = this.totalEstimateAmount.add(amount);
        recalculate();
    }

    public void addPurchaseAmount(BigDecimal amount) {
        this.totalPurchaseAmount = this.totalPurchaseAmount.add(amount);
        recalculate();
    }

    public void subtractEstimateAmount(BigDecimal amount) {
        this.totalEstimateAmount = this.totalEstimateAmount.subtract(amount);
        recalculate();
    }

    public void subtractPurchaseAmount(BigDecimal amount) {
        this.totalPurchaseAmount = this.totalPurchaseAmount.subtract(amount);
        recalculate();
    }

    private void recalculate() {
        this.margin = this.totalEstimateAmount.subtract(this.totalPurchaseAmount);
        if (this.totalEstimateAmount.compareTo(BigDecimal.ZERO) > 0) {
            this.marginRate = this.margin
                    .multiply(BigDecimal.valueOf(100))
                    .divide(this.totalEstimateAmount, 2, java.math.RoundingMode.HALF_UP);
        } else {
            this.marginRate = BigDecimal.ZERO;
        }
    }
}
