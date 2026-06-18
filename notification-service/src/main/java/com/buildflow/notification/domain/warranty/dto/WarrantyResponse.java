package com.buildflow.notification.domain.warranty.dto;

import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.entity.OcrStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private long daysUntilExpiry;
    private boolean expired;
    private String filePath;
    private String memo;
    private OcrStatus ocrStatus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static WarrantyResponse from(DefectWarranty warranty) {
        long daysUntil = warranty.getEndDate() != null
                ? ChronoUnit.DAYS.between(LocalDate.now(), warranty.getEndDate())
                : 0L;
        return WarrantyResponse.builder()
                .id(warranty.getId())
                .siteId(warranty.getSiteId())
                .insuranceCompany(warranty.getInsuranceCompany())
                .policyNumber(warranty.getPolicyNumber())
                .startDate(warranty.getStartDate())
                .endDate(warranty.getEndDate())
                .daysUntilExpiry(Math.max(daysUntil, 0))
                .expired(warranty.isExpired())
                .filePath(warranty.getFilePath())
                .memo(warranty.getMemo())
                .ocrStatus(warranty.getOcrStatus())
                .createdAt(warranty.getCreatedAt())
                .updatedAt(warranty.getUpdatedAt())
                .build();
    }
}
