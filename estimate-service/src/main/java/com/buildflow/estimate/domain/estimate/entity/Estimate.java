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
@Table(name = "estimates")
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

    @OneToMany(mappedBy = "estimate", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EstimateItem> items = new ArrayList<>();

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    public Estimate(Long siteId, String title, LocalDate estimateDate, BigDecimal totalAmount, String memo) {
        this.siteId = siteId;
        this.title = title;
        this.estimateDate = estimateDate;
        this.totalAmount = totalAmount;
        this.memo = memo;
        this.status = EstimateStatus.DRAFT;
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

    public void clearItems() {
        this.items.clear();
    }

    public void addItem(EstimateItem item) {
        this.items.add(item);
    }
}
