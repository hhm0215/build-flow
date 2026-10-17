package com.buildflow.notification.domain.warranty.dto;

import com.buildflow.notification.domain.warranty.entity.OcrStatus;
import com.buildflow.notification.domain.warranty.entity.WarrantyAuditAction;
import com.buildflow.notification.domain.warranty.entity.WarrantyAuditEvent;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
public class WarrantyAuditResponse {

    private Long id;
    private Long warrantyId;
    private WarrantyAuditAction action;
    private String actor;
    private String insuranceCompany;
    private String policyNumber;
    private LocalDate startDate;
    private LocalDate endDate;
    private Long coverageAmount;
    private OcrStatus ocrStatus;
    private Instant expiryConfirmedAt;
    private LocalDateTime occurredAt;

    public static WarrantyAuditResponse from(WarrantyAuditEvent event) {
        return WarrantyAuditResponse.builder()
                .id(event.getId())
                .warrantyId(event.getWarrantyId())
                .action(event.getAction())
                .actor(event.getActor())
                .insuranceCompany(event.getInsuranceCompany())
                .policyNumber(event.getPolicyNumber())
                .startDate(event.getStartDate())
                .endDate(event.getEndDate())
                .coverageAmount(event.getCoverageAmount())
                .ocrStatus(event.getOcrStatus())
                .expiryConfirmedAt(event.getExpiryConfirmedAt())
                .occurredAt(event.getOccurredAt())
                .build();
    }
}
