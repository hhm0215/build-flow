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
import java.util.Optional;

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

    /**
     * PATCH 시맨틱 갱신.
     *
     * <p>필수 필드({@code insuranceCompany}, {@code startDate}, {@code endDate})는 DTO 검증
     * 단계에서 non-null이 보장되므로 항상 새 값으로 대입.
     *
     * <p>선택 필드({@code policyNumber}, {@code coverageAmount}, {@code memo})는 3-state
     * {@link Optional}로 받아 clear/skip/update를 구분한다:
     * <ul>
     *   <li>{@code null} 인자 → 기존 값 유지 (JSON에 필드 없음)</li>
     *   <li>{@code Optional.empty()} → {@code null}로 clear (JSON에 명시적 null)</li>
     *   <li>{@code Optional.of(value)} → 새 값으로 갱신</li>
     * </ul>
     */
    public void update(String insuranceCompany,
                       LocalDate startDate, LocalDate endDate,
                       Optional<String> policyNumber,
                       Optional<Long> coverageAmount,
                       Optional<String> memo) {
        this.insuranceCompany = insuranceCompany;
        this.startDate = startDate;
        this.endDate = endDate;
        if (policyNumber != null) this.policyNumber = policyNumber.orElse(null);
        if (coverageAmount != null) this.coverageAmount = coverageAmount.orElse(null);
        if (memo != null) this.memo = memo.orElse(null);
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

    /** 만료: endDate < today (만료 당일은 아직 expired=false, 다음 날부터 true). */
    public boolean isExpired() {
        return endDate != null && endDate.isBefore(LocalDate.now());
    }
}
