package com.buildflow.notification.domain.notification.dto;

import com.buildflow.notification.domain.notification.entity.Notification;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class NotificationResponse {

    private Long id;
    private String eventType;
    private String message;
    private Long siteId;
    private boolean isRead;
    private LocalDateTime createdAt;

    public static NotificationResponse from(Notification notification) {
        return NotificationResponse.builder()
                .id(notification.getId())
                .eventType(notification.getEventType())
                .message(notification.getMessage())
                .siteId(notification.getSiteId())
                .isRead(notification.isRead())
                .createdAt(notification.getCreatedAt())
                .build();
    }
}
