package com.buildflow.estimate.global.outbox;

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
        @Index(name = "idx_estimate_outbox_due", columnList = "status,next_attempt_at,created_at"),
        @Index(name = "idx_estimate_outbox_lease", columnList = "status,lease_until")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @Column(name = "event_id", length = 36, nullable = false)
    private String eventId;

    @Column(name = "topic", length = 100, nullable = false)
    private String topic;

    @Column(name = "record_key", length = 100, nullable = false)
    private String recordKey;

    @Column(name = "payload_json", columnDefinition = "TEXT", nullable = false)
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private OutboxStatus status;

    @Column(name = "attempts", nullable = false)
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

    @Column(name = "last_error", length = 1000)
    private String lastError;

    public static OutboxEvent pending(String eventId, String topic, String recordKey,
                                      String payloadJson, Instant now) {
        OutboxEvent event = new OutboxEvent();
        event.eventId = eventId;
        event.topic = topic;
        event.recordKey = recordKey;
        event.payloadJson = payloadJson;
        event.status = OutboxStatus.PENDING;
        event.attempts = 0;
        event.nextAttemptAt = now;
        event.createdAt = now;
        return event;
    }

    public void claim(String token, Instant until) {
        if (status != OutboxStatus.PENDING && status != OutboxStatus.CLAIMED) {
            throw new IllegalStateException("발행 불가능한 outbox 상태: " + status);
        }
        status = OutboxStatus.CLAIMED;
        claimToken = token;
        leaseUntil = until;
    }
}
