package com.buildflow.notification.domain.warranty.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 삭제된 보증의 원본 PDF 보존 기록. 파일은 업로드 볼륨 최상위에 그대로 두고(백업 도구가 하위 폴더를 거부),
 * 폐기 예정일이 지나면 정리 작업이 지운다. 전체 경로가 아닌 UUID 파일명만 저장한다.
 */
@Entity
@Table(name = "warranty_file_retentions",
        indexes = @Index(name = "idx_warranty_retention_due", columnList = "purgedAt, purgeAfter"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WarrantyFileRetention {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long warrantyId;

    @Column(nullable = false, length = 64)
    private String storedName;

    @Column(nullable = false)
    private LocalDateTime deletedAt;

    @Column(nullable = false)
    private LocalDate purgeAfter;

    private LocalDateTime purgedAt;

    public static WarrantyFileRetention retain(Long warrantyId, String storedName,
                                               LocalDateTime deletedAt, LocalDate purgeAfter) {
        WarrantyFileRetention retention = new WarrantyFileRetention();
        retention.warrantyId = warrantyId;
        retention.storedName = storedName;
        retention.deletedAt = deletedAt;
        retention.purgeAfter = purgeAfter;
        return retention;
    }

    public void markPurged(LocalDateTime now) {
        this.purgedAt = now;
    }
}
