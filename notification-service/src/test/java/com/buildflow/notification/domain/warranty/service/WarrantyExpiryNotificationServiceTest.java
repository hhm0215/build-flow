package com.buildflow.notification.domain.warranty.service;

import com.buildflow.notification.domain.notification.service.NotificationService;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class WarrantyExpiryNotificationServiceTest {

    @Test
    void databaseFailurePropagatesForKafkaRetry() {
        DefectWarrantyRepository warranties = mock(DefectWarrantyRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        WarrantyExpiryNotificationService service = new WarrantyExpiryNotificationService(warranties, notifications);
        when(warranties.findByIdForUpdate(5L)).thenThrow(new IllegalStateException("DB unavailable"));

        assertThatThrownBy(() -> service.consume("event", 5L, 7L, "보험사", LocalDate.now(), null))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(notifications);
    }
}
