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

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    public Site(String siteName, Client client, String address,
                LocalDate startDate, LocalDate endDate, String memo) {
        this.siteName = siteName;
        this.client = client;
        this.address = address;
        this.status = SiteStatus.IN_PROGRESS;
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
}
