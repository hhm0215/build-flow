package com.buildflow.notification.domain.warranty.dto;

import com.buildflow.notification.domain.warranty.WarrantyDatePolicy;
import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.entity.OcrStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Getter
@Builder
public class WarrantyResponse {

    private Long id;
    private Long siteId;
    private String insuranceCompany;
    private String policyNumber;
    private LocalDate startDate;
    private LocalDate endDate;
    private Long coverageAmount;
    private long daysUntilExpiry;
    private boolean expired;
    private boolean expiryConfirmed;
    private Instant expiryConfirmedAt;
    private String memo;
    private OcrStatus ocrStatus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static WarrantyResponse from(DefectWarranty warranty) {
        return from(warranty, WarrantyDatePolicy.today());
    }

    public static WarrantyResponse from(DefectWarranty warranty, LocalDate today) {
        boolean expiryConfirmed = warranty.getOcrStatus() == OcrStatus.MANUAL
                && warranty.getExpiryConfirmedAt() != null
                && warranty.getInsuranceCompany() != null && !warranty.getInsuranceCompany().isBlank()
                && warranty.getStartDate() != null && warranty.getEndDate() != null
                && !warranty.getStartDate().isAfter(warranty.getEndDate());
        long daysUntil = expiryConfirmed
                ? ChronoUnit.DAYS.between(today, warranty.getEndDate())
                : 0L;
        return WarrantyResponse.builder()
                .id(warranty.getId())
                .siteId(warranty.getSiteId())
                .insuranceCompany(warranty.getInsuranceCompany())
                .policyNumber(warranty.getPolicyNumber())
                .startDate(warranty.getStartDate())
                .endDate(warranty.getEndDate())
                .coverageAmount(warranty.getCoverageAmount())
                .daysUntilExpiry(Math.max(daysUntil, 0))
                .expired(expiryConfirmed && warranty.isExpired(today))
                .expiryConfirmed(expiryConfirmed)
                .expiryConfirmedAt(warranty.getExpiryConfirmedAt())
                .memo(warranty.getMemo())
                .ocrStatus(warranty.getOcrStatus())
                .createdAt(warranty.getCreatedAt())
                .updatedAt(warranty.getUpdatedAt())
                .build();
    }
}
