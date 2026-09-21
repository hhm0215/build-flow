package com.buildflow.purchase.global.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "outbox_events", indexes = {
        @Index(name = "idx_outbox_pending", columnList = "status,next_attempt_at,created_at"),
        @Index(name = "idx_outbox_lease", columnList = "status,lease_until")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false, length = 36)
    private String eventId;

    @Column(nullable = false, updatable = false, length = 100)
    private String topic;

    @Column(name = "record_key", nullable = false, updatable = false, length = 100)
    private String recordKey;

    @Column(name = "payload_json", nullable = false, updatable = false, columnDefinition = "LONGTEXT")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "claim_token", length = 36)
    private String claimToken;

    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    public OutboxEvent(String eventId, String topic, String recordKey, String payloadJson) {
        this.eventId = eventId;
        this.topic = topic;
        this.recordKey = recordKey;
        this.payloadJson = payloadJson;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.createdAt = LocalDateTime.now();
        this.nextAttemptAt = createdAt;
    }

    public String claim(LocalDateTime now, LocalDateTime leaseExpiry) {
        if ((status == OutboxStatus.PENDING && nextAttemptAt.isAfter(now))
                || (status == OutboxStatus.CLAIMED && (leaseUntil == null || leaseUntil.isAfter(now)))
                || status == OutboxStatus.SENT) {
            throw new IllegalStateException("발행할 수 없는 outbox 상태입니다.");
        }
        this.status = OutboxStatus.CLAIMED;
        this.attempts += 1;
        this.claimToken = UUID.randomUUID().toString();
        this.leaseUntil = leaseExpiry;
        this.lastError = null;
        return claimToken;
    }

    public boolean isClaimedBy(String token) {
        return status == OutboxStatus.CLAIMED && token != null && token.equals(claimToken);
    }

    public void markSent(LocalDateTime now) {
        this.status = OutboxStatus.SENT;
        this.sentAt = now;
        this.claimToken = null;
        this.leaseUntil = null;
        this.lastError = null;
    }

    public void releaseForRetry(LocalDateTime nextAttemptAt, String error) {
        this.status = OutboxStatus.PENDING;
        this.nextAttemptAt = nextAttemptAt;
        this.claimToken = null;
        this.leaseUntil = null;
        this.lastError = error == null ? null : error.substring(0, Math.min(500, error.length()));
    }
}
