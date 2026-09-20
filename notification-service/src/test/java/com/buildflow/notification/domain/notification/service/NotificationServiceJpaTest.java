package com.buildflow.notification.domain.notification.service;

import com.buildflow.notification.domain.notification.entity.Notification;
import com.buildflow.notification.domain.notification.repository.NotificationRepository;
import com.buildflow.notification.domain.notification.repository.ProcessedNotificationEventRepository;
import com.buildflow.notification.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@DataJpaTest
@ActiveProfiles("test")
@Import({NotificationService.class, JpaAuditingConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationServiceJpaTest {

    @Autowired private NotificationService notificationService;
    @SpyBean private NotificationRepository notificationRepository;
    @Autowired private ProcessedNotificationEventRepository processedEventRepository;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        processedEventRepository.deleteAll();
    }

    @Test
    void duplicateEventCreatesOneReceiptAndOneNotification() {
        String eventId = UUID.randomUUID().toString();

        notificationService.createNotification(eventId, "ESTIMATE_PARSED", "견적 확정", 7L);
        notificationService.createNotification(eventId, "ESTIMATE_PARSED", "견적 확정", 7L);

        assertEquals(1, processedEventRepository.count());
        assertEquals(1, notificationRepository.count());
    }

    @Test
    void notificationWriteFailureRollsBackAlreadyFlushedReceipt() {
        String eventId = UUID.randomUUID().toString();
        doThrow(new IllegalStateException("notification write failed"))
                .when(notificationRepository).save(any(Notification.class));

        assertThrows(IllegalStateException.class, () -> notificationService.createNotification(
                eventId, "ESTIMATE_PARSED", "견적 확정", 7L));

        assertFalse(processedEventRepository.existsById(eventId));
        assertEquals(0, notificationRepository.count());
    }
}
