package com.buildflow.site.domain.site.entity;

import com.buildflow.site.domain.client.entity.Client;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static lombok.AccessLevel.PROTECTED;

@Entity
@Table(name = "sites")
@Getter
@NoArgsConstructor(access = PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Site {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String siteName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private Client client;

    @Column(length = 500)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SiteStatus status;

    private LocalDate startDate;

    private LocalDate endDate;

    @Column(columnDefinition = "TEXT")
    private String memo;

    // 보관 시각(KST). null이면 미보관. 업무 단계(status)와 별개이며 보관해도 자료·손익은 유지한다 (ADR-021)
    private LocalDateTime archivedAt;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    public Site(String siteName, Client client, String address,
                LocalDate startDate, LocalDate endDate, String memo, SiteStatus status) {
        this.siteName = siteName;
        this.client = client;
        this.address = address;
        this.status = status != null ? status : SiteStatus.IN_PROGRESS;
        this.startDate = startDate;
        this.endDate = endDate;
        this.memo = memo;
    }

    public void update(String siteName, Client client, String address,
                       LocalDate startDate, LocalDate endDate, String memo) {
        this.siteName = siteName;
        this.client = client;
        this.address = address;
        this.startDate = startDate;
        this.endDate = endDate;
        this.memo = memo;
    }

    public void changeStatus(SiteStatus status) {
        this.status = status;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    /** 이미 보관된 현장이면 최초 보관 시각을 유지한다. */
    public void archive(LocalDateTime now) {
        if (archivedAt == null) {
            archivedAt = now;
        }
    }

    public void restore() {
        archivedAt = null;
    }
}
