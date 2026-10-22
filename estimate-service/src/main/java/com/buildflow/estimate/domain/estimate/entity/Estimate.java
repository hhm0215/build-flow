package com.buildflow.estimate.domain.estimate.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "estimates", indexes = @Index(name = "idx_estimates_chain_root", columnList = "chain_root_id"))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Estimate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long siteId;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstimateStatus status;

    @Column(nullable = false)
    private LocalDate estimateDate;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount;

    @Column(length = 1000)
    private String memo;

    /** 기존 행은 NULL(= ORIGINAL). */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private EstimateKind kind;

    /** 같은 현장의 원 견적 id. REVISION은 대체 대상, ADDITIONAL은 추가공사의 기준 견적. 생성 후 불변. */
    @Column(name = "revision_of_id")
    private Long revisionOfId;

    /** 수정 체인 루트 id. NULL이면 자기 자신이 루트(ORIGINAL·ADDITIONAL·기존 행). 생성 후 불변. */
    @Column(name = "chain_root_id")
    private Long chainRootId;

    /** 이 견적을 대체한 확정 수정본 id. SUPERSEDED일 때만 값이 있다. */
    @Column(name = "superseded_by_id")
    private Long supersededById;

    @OneToMany(mappedBy = "estimate", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EstimateItem> items = new ArrayList<>();

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    public Estimate(Long siteId, String title, LocalDate estimateDate, BigDecimal totalAmount, String memo,
                    EstimateKind kind, Long revisionOfId, Long chainRootId) {
        this.siteId = siteId;
        this.title = title;
        this.estimateDate = estimateDate;
        this.totalAmount = totalAmount;
        this.memo = memo;
        this.kind = kind == null ? EstimateKind.ORIGINAL : kind;
        this.revisionOfId = revisionOfId;
        this.chainRootId = chainRootId;
        this.status = EstimateStatus.DRAFT;
    }

    public EstimateKind effectiveKind() {
        return kind == null ? EstimateKind.ORIGINAL : kind;
    }

    /** 수정 체인 잠금·회계 유효 버전 판단의 기준 id. */
    public Long effectiveChainRootId() {
        return chainRootId == null ? id : chainRootId;
    }

    public void update(String title, LocalDate estimateDate, BigDecimal totalAmount, String memo) {
        this.title = title;
        this.estimateDate = estimateDate;
        this.totalAmount = totalAmount;
        this.memo = memo;
    }

    public void confirm() {
        this.status = EstimateStatus.CONFIRMED;
    }

    /** 확정본을 대체됨으로 바꾼다. 금액·항목·이력은 그대로 남긴다. */
    public void supersede(Long replacementId) {
        if (this.status != EstimateStatus.CONFIRMED) {
            throw new IllegalStateException("확정된 견적만 대체할 수 있습니다.");
        }
        this.status = EstimateStatus.SUPERSEDED;
        this.supersededById = replacementId;
    }

    public void clearItems() {
        this.items.clear();
    }

    public void addItem(EstimateItem item) {
        this.items.add(item);
    }
}
