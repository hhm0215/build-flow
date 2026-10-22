package com.buildflow.site.domain.profit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "purchase_profit_projections", indexes = {
        @Index(name = "idx_purchase_profit_projection_site", columnList = "site_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseProfitProjection {

    @Id
    private Long purchaseId;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Column(nullable = false)
    private Long lastRevision;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal currentAmount;

    @Column(nullable = false)
    private boolean deleted;

    @Column(nullable = false, length = 36)
    private String lastEventId;

    public PurchaseProfitProjection(Long purchaseId, Long siteId, long revision,
                                    BigDecimal currentAmount, boolean deleted, String eventId) {
        this.purchaseId = purchaseId;
        this.siteId = siteId;
        this.lastRevision = revision;
        this.currentAmount = currentAmount;
        this.deleted = deleted;
        this.lastEventId = eventId;
    }

    public BigDecimal contribution() {
        return deleted ? BigDecimal.ZERO : currentAmount;
    }

    public boolean hasSameState(Long candidateSiteId, BigDecimal candidateAmount,
                                boolean candidateDeleted) {
        return siteId.equals(candidateSiteId)
                && currentAmount.compareTo(candidateAmount) == 0
                && deleted == candidateDeleted;
    }

    /**
     * 손익 재계산에서 원천에 행이 없는(삭제된) 매입. 원천은 삭제 revision을 남기지 않으므로 최대 revision으로
     * 고정해 늦게 도착하는 같은 매입의 모든 이벤트가 "지연 도착"으로 무시되게 한다(매입 id는 재사용되지 않음).
     */
    public static final long RECONCILED_TOMBSTONE_REVISION = Long.MAX_VALUE;

    public void markReconciledDeleted(String reconcileId) {
        replace(RECONCILED_TOMBSTONE_REVISION, currentAmount, true, reconcileId);
    }

    public void replace(long revision, BigDecimal amount, boolean deleted, String eventId) {
        this.lastRevision = revision;
        this.currentAmount = amount;
        this.deleted = deleted;
        this.lastEventId = eventId;
    }
}
