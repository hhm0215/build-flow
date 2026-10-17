package com.buildflow.site.domain.client.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

import static lombok.AccessLevel.PROTECTED;

@Entity
@Table(name = "clients")
@Getter
@NoArgsConstructor(access = PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String companyName;

    @Column(length = 50)
    private String representative;

    @Column(length = 20)
    private String businessNo;

    @Column(length = 20)
    private String phone;

    @Column(length = 100)
    private String email;

    @Column(length = 500)
    private String address;

    @Column(columnDefinition = "TEXT")
    private String memo;

    // 보관 시각(KST). null이면 미보관. 보관해도 연결된 현장·자료는 그대로 유지한다 (ADR-021)
    private LocalDateTime archivedAt;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    public Client(String companyName, String representative, String businessNo,
                  String phone, String email, String address, String memo) {
        this.companyName = companyName;
        this.representative = representative;
        this.businessNo = businessNo;
        this.phone = phone;
        this.email = email;
        this.address = address;
        this.memo = memo;
    }

    public void update(String companyName, String representative, String businessNo,
                       String phone, String email, String address, String memo) {
        this.companyName = companyName;
        this.representative = representative;
        this.businessNo = businessNo;
        this.phone = phone;
        this.email = email;
        this.address = address;
        this.memo = memo;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    /** 이미 보관된 거래처면 최초 보관 시각을 유지한다. */
    public void archive(LocalDateTime now) {
        if (archivedAt == null) {
            archivedAt = now;
        }
    }

    public void restore() {
        archivedAt = null;
    }
}
