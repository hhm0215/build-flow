package com.buildflow.notification.domain.warranty.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "defect_warranties")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class DefectWarranty {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long siteId;

    @Column(nullable = false, length = 200)
    private String insuranceCompany;

    @Column(length = 100)
    private String policyNumber;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(length = 500)
    private String filePath;

    @Column(columnDefinition = "TEXT")
    private String memo;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    private DefectWarranty(Long siteId, String insuranceCompany, String policyNumber,
                           LocalDate startDate, LocalDate endDate,
                           String filePath, String memo) {
        this.siteId = siteId;
        this.insuranceCompany = insuranceCompany;
        this.policyNumber = policyNumber;
        this.startDate = startDate;
        this.endDate = endDate;
        this.filePath = filePath;
        this.memo = memo;
    }

    public void update(String insuranceCompany, String policyNumber,
                       LocalDate startDate, LocalDate endDate, String memo) {
        this.insuranceCompany = insuranceCompany;
        this.policyNumber = policyNumber;
        this.startDate = startDate;
        this.endDate = endDate;
        this.memo = memo;
    }

    public boolean isExpiringSoon(int daysBeforeExpiry) {
        return LocalDate.now().plusDays(daysBeforeExpiry).isAfter(endDate)
                && LocalDate.now().isBefore(endDate);
    }

    public boolean isExpired() {
        return LocalDate.now().isAfter(endDate);
    }
}
