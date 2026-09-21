package com.buildflow.tax.global.outbox;

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

@Entity
@Table(name = "outbox_events", indexes =
        @Index(name = "idx_outbox_pending", columnList = "status,next_attempt_at,created_at"))
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

    @Column(name = "payload_json", nullable = false, columnDefinition = "LONGTEXT", updatable = false)
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
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

    @Column(name = "last_error", length = 1000)
    private String lastError;

    public OutboxEvent(String eventId, String topic, String recordKey, String payloadJson,
                       LocalDateTime createdAt) {
        this.eventId = eventId;
        this.topic = topic;
        this.recordKey = recordKey;
        this.payloadJson = payloadJson;
        this.status = OutboxStatus.PENDING;
        this.nextAttemptAt = createdAt;
        this.createdAt = createdAt;
    }

    public void claim(String token, LocalDateTime leaseUntil) {
        this.status = OutboxStatus.CLAIMED;
        this.claimToken = token;
        this.leaseUntil = leaseUntil;
        this.attempts++;
    }
}
