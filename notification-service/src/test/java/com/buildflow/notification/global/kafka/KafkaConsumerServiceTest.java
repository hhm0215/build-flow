package com.buildflow.notification.global.kafka;

import com.buildflow.notification.domain.notification.service.NotificationService;
import com.buildflow.notification.domain.warranty.service.WarrantyExpiryNotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class KafkaConsumerServiceTest {

    private static final String EVENT_ID = "11111111-1111-4111-8111-111111111111";

    private final NotificationService notificationService = mock(NotificationService.class);
    private final WarrantyExpiryNotificationService warrantyExpiryNotificationService =
            mock(WarrantyExpiryNotificationService.class);
    private final KafkaConsumerService consumer = new KafkaConsumerService(
            notificationService, warrantyExpiryNotificationService, new ObjectMapper());

    @Test
    void validEventPassesEventIdToTransactionalService() {
        consumer.consumeEstimateParsed(event("ESTIMATE_PARSED", "\"siteId\":7,\"estimateId\":11"));

        verify(notificationService).createNotification(EVENT_ID, "ESTIMATE_PARSED",
                "견적서(ID: 11)가 확정되었습니다.", 7L);
    }

    @Test
    void malformedAndMissingIdEventsPropagateInsteadOfBeingAcked() {
        assertThatThrownBy(() -> consumer.consumeEstimateParsed("not-json"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer.consumeEstimateParsed(
                "{\"eventType\":\"ESTIMATE_PARSED\",\"payload\":{\"siteId\":7,\"estimateId\":11}}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer.consumeEstimateParsed(
                "{\"eventId\":\"not-a-uuid\",\"eventType\":\"ESTIMATE_PARSED\","
                        + "\"payload\":{\"siteId\":7,\"estimateId\":11}}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer.consumeEstimateParsed(
                event("ESTIMATE_PARSED", "\"siteId\":7")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(notificationService);
    }

    @Test
    void wrongEventTypeAndBadTaxTypePropagate() {
        assertThatThrownBy(() -> consumer.consumeEstimateParsed(
                event("PURCHASE_REGISTERED", "\"siteId\":7,\"estimateId\":11")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer.consumeTaxRegistered(
                event("TAX_REGISTERED", "\"siteId\":7,\"taxInvoiceId\":11,\"type\":\"OTHER\"")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(notificationService);
    }

    @Test
    void businessFailurePropagates() {
        doThrow(new IllegalStateException("DB unavailable")).when(notificationService)
                .createNotification(anyString(), anyString(), anyString(), anyLong());

        assertThatThrownBy(() -> consumer.consumePurchaseRegistered(
                event("PURCHASE_REGISTERED", "\"siteId\":7,\"purchaseId\":2")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void warrantyEventPassesSnapshotToTransactionalRecheck() {
        consumer.consumeWarrantyExpiring(event("WARRANTY_EXPIRING",
                "\"warrantyId\":5,\"siteId\":7,\"insuranceCompany\":\"보험사\","
                        + "\"endDate\":\"2026-11-01\",\"daysUntilExpiry\":18"));

        verify(warrantyExpiryNotificationService).consume(EVENT_ID, 5L, 7L,
                "보험사", java.time.LocalDate.of(2026, 11, 1));
        verifyNoInteractions(notificationService);
    }

    @Test
    void malformedWarrantySnapshotIsNotAcknowledged() {
        assertThatThrownBy(() -> consumer.consumeWarrantyExpiring(event("WARRANTY_EXPIRING",
                "\"warrantyId\":5,\"siteId\":7,\"insuranceCompany\":\"보험사\","
                        + "\"endDate\":\"bad-date\",\"daysUntilExpiry\":18")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(warrantyExpiryNotificationService);
    }

    private String event(String type, String payloadFields) {
        return "{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"" + type
                + "\",\"payload\":{" + payloadFields + "}}";
    }
}
