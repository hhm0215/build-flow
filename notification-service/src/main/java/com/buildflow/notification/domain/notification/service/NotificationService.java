package com.buildflow.notification.domain.notification.service;

import com.buildflow.notification.domain.notification.dto.NotificationResponse;
import com.buildflow.notification.domain.notification.entity.Notification;
import com.buildflow.notification.domain.notification.entity.ProcessedNotificationEvent;
import com.buildflow.notification.domain.notification.repository.NotificationRepository;
import com.buildflow.notification.domain.notification.repository.ProcessedNotificationEventRepository;
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
    private final ProcessedNotificationEventRepository processedEventRepository;

    @Transactional
    public void createNotification(String eventId, String eventType, String message, Long siteId) {
        if (processedEventRepository.existsById(eventId)) {
            log.info("이미 처리한 알림 이벤트: eventId={}", eventId);
            return;
        }
        // DB UNIQUE 제약이 동시 중복 요청의 마지막 방어선이다. 충돌은 재시도로 보내고
        // 다음 시도에서 이미 처리한 이벤트로 판단한다.
        processedEventRepository.saveAndFlush(new ProcessedNotificationEvent(eventId));
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
