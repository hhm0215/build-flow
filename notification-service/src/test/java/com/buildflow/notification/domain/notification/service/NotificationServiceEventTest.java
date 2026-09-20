package com.buildflow.notification.domain.notification.service;

import com.buildflow.notification.domain.notification.entity.Notification;
import com.buildflow.notification.domain.notification.entity.ProcessedNotificationEvent;
import com.buildflow.notification.domain.notification.repository.NotificationRepository;
import com.buildflow.notification.domain.notification.repository.ProcessedNotificationEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationServiceEventTest {

    private static final String EVENT_ID = "11111111-1111-4111-8111-111111111111";

    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final ProcessedNotificationEventRepository processedEventRepository =
            mock(ProcessedNotificationEventRepository.class);
    private final NotificationService service = new NotificationService(notificationRepository, processedEventRepository);

    @Test
    void firstEventStoresReceiptAndNotification() {
        service.createNotification(EVENT_ID, "ESTIMATE_PARSED", "확정", 7L);

        var order = inOrder(processedEventRepository, notificationRepository);
        order.verify(processedEventRepository).existsById(EVENT_ID);
        order.verify(processedEventRepository).saveAndFlush(any(ProcessedNotificationEvent.class));
        order.verify(notificationRepository).save(any(Notification.class));
    }

    @Test
    void duplicateEventDoesNotCreateNotification() {
        when(processedEventRepository.existsById(EVENT_ID)).thenReturn(true);

        service.createNotification(EVENT_ID, "ESTIMATE_PARSED", "확정", 7L);

        verify(processedEventRepository, never()).saveAndFlush(any());
        verifyNoInteractions(notificationRepository);
    }

    @Test
    void persistenceFailurePropagatesForKafkaRetry() throws NoSuchMethodException {
        when(notificationRepository.save(any(Notification.class)))
                .thenThrow(new IllegalStateException("DB unavailable"));

        assertThatThrownBy(() -> service.createNotification(EVENT_ID, "ESTIMATE_PARSED", "확정", 7L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(NotificationService.class.getMethod("createNotification",
                String.class, String.class, String.class, Long.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }
}
