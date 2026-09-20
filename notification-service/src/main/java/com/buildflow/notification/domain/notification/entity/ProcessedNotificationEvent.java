package com.buildflow.notification.domain.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "processed_notification_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedNotificationEvent {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String eventId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime processedAt;

    public ProcessedNotificationEvent(String eventId) {
        this.eventId = eventId;
        this.processedAt = LocalDateTime.now();
    }
}
