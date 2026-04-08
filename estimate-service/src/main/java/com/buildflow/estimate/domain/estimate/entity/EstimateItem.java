package com.buildflow.estimate.domain.estimate.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "estimate_items")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class EstimateItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "estimate_id", nullable = false)
    private Estimate estimate;

    @Column(nullable = false, length = 200)
    private String itemName;

    @Column(nullable = false, length = 20)
    private String unit;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Builder
    public EstimateItem(Estimate estimate, String itemName, String unit,
                        BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
        this.estimate = estimate;
        this.itemName = itemName;
        this.unit = unit;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.amount = amount;
    }
}
