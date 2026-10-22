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

    // margin_rate는 DECIMAL(5,2). 매입이 견적의 10배를 넘는 손실 현장은 이 범위를 벗어나 저장이 실패하므로
    // 마진 금액은 정확히 두고 표시용 마진율만 하한으로 제한한다(컬럼 확장은 마이그레이션 게이트 대상).
    static final BigDecimal MIN_MARGIN_RATE = new BigDecimal("-999.99");

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

    public void subtractEstimateAmount(BigDecimal amount) {
        this.totalEstimateAmount = this.totalEstimateAmount.subtract(amount);
        recalculate();
    }

    public void adjustPurchaseAmount(BigDecimal delta) {
        BigDecimal adjusted = this.totalPurchaseAmount.add(delta);
        if (adjusted.signum() < 0) {
            throw new IllegalStateException("현장 총매입은 음수가 될 수 없습니다.");
        }
        this.totalPurchaseAmount = adjusted;
        recalculate();
    }

    /**
     * 손익 재계산: 원천 합계로 덮어쓰고 마진·마진율은 이벤트 경로와 같은 규칙으로 다시 계산한다.
     */
    public void overwriteTotals(BigDecimal estimateAmount, BigDecimal purchaseAmount) {
        if (estimateAmount == null || purchaseAmount == null
                || estimateAmount.signum() < 0 || purchaseAmount.signum() < 0) {
            throw new IllegalArgumentException("손익 원천 합계는 0 이상이어야 합니다.");
        }
        this.totalEstimateAmount = estimateAmount;
        this.totalPurchaseAmount = purchaseAmount;
        recalculate();
    }

    private void recalculate() {
        this.margin = this.totalEstimateAmount.subtract(this.totalPurchaseAmount);
        if (this.totalEstimateAmount.compareTo(BigDecimal.ZERO) > 0) {
            this.marginRate = this.margin
                    .multiply(BigDecimal.valueOf(100))
                    .divide(this.totalEstimateAmount, 2, java.math.RoundingMode.HALF_UP)
                    .max(MIN_MARGIN_RATE);
        } else {
            this.marginRate = BigDecimal.ZERO;
        }
    }
}
