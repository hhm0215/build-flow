package com.buildflow.notification.domain.warranty.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 보증 값 변경의 추가 전용 이력(누가·언제·어떤 값으로). 보증이 삭제돼도 남기려고 외래키를 두지 않는다.
 * 파일 경로처럼 내부 정보는 담지 않는다. 공유 관리자 계정이라 actor는 계정 단위이며 실제 사람을 증명하지 않는다.
 */
@Entity
@Table(name = "warranty_audit_events", indexes = @Index(name = "idx_warranty_audit_warranty", columnList = "warrantyId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WarrantyAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long warrantyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WarrantyAuditAction action;

    @Column(nullable = false, length = 64)
    private String actor;

    @Column(length = 200)
    private String insuranceCompany;

    @Column(length = 100)
    private String policyNumber;

    private LocalDate startDate;

    private LocalDate endDate;

    private Long coverageAmount;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private OcrStatus ocrStatus;

    private Instant expiryConfirmedAt;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    public static WarrantyAuditEvent snapshot(WarrantyAuditAction action, String actor,
                                              DefectWarranty warranty, LocalDateTime occurredAt) {
        WarrantyAuditEvent event = new WarrantyAuditEvent();
        event.warrantyId = warranty.getId();
        event.action = action;
        event.actor = actor;
        event.insuranceCompany = warranty.getInsuranceCompany();
        event.policyNumber = warranty.getPolicyNumber();
        event.startDate = warranty.getStartDate();
        event.endDate = warranty.getEndDate();
        event.coverageAmount = warranty.getCoverageAmount();
        event.ocrStatus = warranty.getOcrStatus();
        event.expiryConfirmedAt = warranty.getExpiryConfirmedAt();
        event.occurredAt = occurredAt;
        return event;
    }
}
