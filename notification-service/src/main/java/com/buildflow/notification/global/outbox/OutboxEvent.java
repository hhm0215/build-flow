package com.buildflow.notification.global.outbox;

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

import java.time.Instant;

@Entity
@Table(name = "outbox_events", indexes = {
        @Index(name = "idx_outbox_due", columnList = "status,next_attempt_at"),
        @Index(name = "idx_outbox_record", columnList = "topic,record_key,status")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @Column(name = "event_id", length = 36, nullable = false, updatable = false)
    private String eventId;

    @Column(nullable = false, length = 100, updatable = false)
    private String topic;

    @Column(name = "record_key", nullable = false, length = 100, updatable = false)
    private String recordKey;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "claim_token", length = 36)
    private String claimToken;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    public OutboxEvent(String eventId, String topic, String recordKey, String payloadJson, Instant now) {
        this.eventId = eventId;
        this.topic = topic;
        this.recordKey = recordKey;
        this.payloadJson = payloadJson;
        this.status = OutboxStatus.PENDING;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    public void claim(String token, Instant leaseEnd) {
        this.status = OutboxStatus.CLAIMED;
        this.claimToken = token;
        this.leaseUntil = leaseEnd;
        this.attempts++;
    }

    public boolean markSent(String token, Instant now) {
        if (!isClaimedBy(token)) return false;
        this.status = OutboxStatus.SENT;
        this.sentAt = now;
        this.claimToken = null;
        this.leaseUntil = null;
        this.lastError = null;
        return true;
    }

    public boolean markFailed(String token, Instant now, String error) {
        if (!isClaimedBy(token)) return false;
        long delaySeconds = Math.min(300L, 1L << Math.min(8, Math.max(0, attempts - 1)));
        this.status = OutboxStatus.PENDING;
        this.nextAttemptAt = now.plusSeconds(delaySeconds);
        this.claimToken = null;
        this.leaseUntil = null;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
        return true;
    }

    private boolean isClaimedBy(String token) {
        return status == OutboxStatus.CLAIMED && token != null && token.equals(claimToken);
    }
}
