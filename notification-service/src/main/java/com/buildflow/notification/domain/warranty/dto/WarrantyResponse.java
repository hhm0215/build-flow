package com.buildflow.notification.domain.warranty.dto;

import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static WarrantyResponse from(DefectWarranty warranty) {
        long daysUntil = ChronoUnit.DAYS.between(LocalDate.now(), warranty.getEndDate());
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
                .createdAt(warranty.getCreatedAt())
                .updatedAt(warranty.getUpdatedAt())
                .build();
    }
}
