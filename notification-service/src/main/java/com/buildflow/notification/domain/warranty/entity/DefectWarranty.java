package com.buildflow.notification.domain.warranty.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
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

    @Column(length = 200)
    private String insuranceCompany;

    @Column(length = 100)
    private String policyNumber;

    @Column
    private LocalDate startDate;

    @Column
    private LocalDate endDate;

    @Column
    private Long coverageAmount;

    @Column(length = 500)
    private String filePath;

    @Column(columnDefinition = "TEXT")
    private String memo;

    @Column
    private LocalDate lastExpiringAlertSentAt;

    /**
     * OCR 처리 상태.
     * ColumnDefault: ddl-auto=update로 기존 운영 데이터에 컬럼 추가 시
     * DEFAULT 'MANUAL'로 backfill되어 NOT NULL ALTER 실패 방지.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @ColumnDefault("'MANUAL'")
    private OcrStatus ocrStatus;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    private DefectWarranty(Long siteId, String insuranceCompany, String policyNumber,
                           LocalDate startDate, LocalDate endDate,
                           Long coverageAmount, String filePath, String memo) {
        this.siteId = siteId;
        this.insuranceCompany = insuranceCompany;
        this.policyNumber = policyNumber;
        this.startDate = startDate;
        this.endDate = endDate;
        this.coverageAmount = coverageAmount;
        this.filePath = filePath;
        this.memo = memo;
        this.ocrStatus = OcrStatus.MANUAL;
    }

    /** OCR 흐름 진입점 — 최소 정보(siteId + filePath)만으로 PENDING 레코드 생성 */
    public static DefectWarranty createPending(Long siteId, String filePath) {
        DefectWarranty w = new DefectWarranty();
        w.siteId = siteId;
        w.filePath = filePath;
        w.ocrStatus = OcrStatus.PENDING;
        return w;
    }

    public void update(String insuranceCompany, String policyNumber,
                       LocalDate startDate, LocalDate endDate,
                       Long coverageAmount, String memo) {
        this.insuranceCompany = insuranceCompany;
        this.policyNumber = policyNumber;
        this.startDate = startDate;
        this.endDate = endDate;
        this.coverageAmount = coverageAmount;
        this.memo = memo;
    }

    /**
     * OCR 추출 결과 반영. 4 필드 중 1개 이상 추출됐을 때만 SUCCESS.
     * 모두 null이면 FAILED로 마킹 — 사용자가 수동 입력으로 보완해야 함.
     */
    public void applyOcrResult(String insuranceCompany, String policyNumber,
                               LocalDate startDate, LocalDate endDate) {
        if (insuranceCompany != null) this.insuranceCompany = insuranceCompany;
        if (policyNumber != null) this.policyNumber = policyNumber;
        if (startDate != null) this.startDate = startDate;
        if (endDate != null) this.endDate = endDate;
        boolean anyExtracted = insuranceCompany != null || policyNumber != null
                || startDate != null || endDate != null;
        this.ocrStatus = anyExtracted ? OcrStatus.SUCCESS : OcrStatus.FAILED;
    }

    public void markOcrFailed() {
        this.ocrStatus = OcrStatus.FAILED;
    }

    public void markExpiringAlertSent(LocalDate sentAt) {
        this.lastExpiringAlertSentAt = sentAt;
    }

    public boolean isExpiringSoon(int daysBeforeExpiry) {
        return endDate != null
                && LocalDate.now().plusDays(daysBeforeExpiry).isAfter(endDate)
                && LocalDate.now().isBefore(endDate);
    }

    public boolean isExpired() {
        return endDate != null && LocalDate.now().isAfter(endDate);
    }
}
