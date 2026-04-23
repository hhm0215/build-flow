package com.buildflow.notification.domain.notification.service;

import com.buildflow.notification.domain.notification.dto.NotificationResponse;
import com.buildflow.notification.domain.notification.entity.Notification;
import com.buildflow.notification.domain.notification.repository.NotificationRepository;
import com.buildflow.notification.global.exception.BusinessException;
import com.buildflow.notification.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationRepository notificationRepository;

    @Transactional
    public void createNotification(String eventType, String message, Long siteId) {
        Notification notification = Notification.builder()
                .eventType(eventType)
                .message(message)
                .siteId(siteId)
                .build();
        notificationRepository.save(notification);
        log.info("알림 생성: eventType={}, siteId={}", eventType, siteId);
    }

    public List<NotificationResponse> findAll(Boolean unreadOnly) {
        List<Notification> notifications = (unreadOnly != null && unreadOnly)
                ? notificationRepository.findByIsReadFalseOrderByCreatedAtDesc()
                : notificationRepository.findAllByOrderByCreatedAtDesc();

        return notifications.stream()
                .map(NotificationResponse::from)
                .toList();
    }

    public long getUnreadCount() {
        return notificationRepository.countByIsReadFalse();
    }

    @Transactional
    public NotificationResponse markAsRead(Long id) {
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));
        notification.markAsRead();
        return NotificationResponse.from(notification);
    }

    @Transactional
    public void markAllAsRead() {
        List<Notification> unread = notificationRepository.findByIsReadFalseOrderByCreatedAtDesc();
        unread.forEach(Notification::markAsRead);
        log.info("전체 알림 읽음 처리: {}건", unread.size());
    }
}
