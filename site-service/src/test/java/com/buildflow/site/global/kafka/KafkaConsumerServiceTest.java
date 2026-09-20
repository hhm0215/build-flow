package com.buildflow.site.global.kafka;

import com.buildflow.site.domain.profit.event.ProfitEventType;
import com.buildflow.site.domain.profit.service.ProfitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class KafkaConsumerServiceTest {

    private static final String EVENT_ID = "00000000-0000-0000-0000-000000000001";

    private ProfitService profitService;
    private KafkaConsumerService consumer;

    @BeforeEach
    void setUp() {
        profitService = mock(ProfitService.class);
        consumer = new KafkaConsumerService(profitService, new ObjectMapper());
    }

    @Test
    void parsesAllProfitEventsAndPassesAmounts() {
        consumer.consumeEstimateParsed(message("ESTIMATE_PARSED",
                "{\"estimateId\":1,\"siteId\":2,\"totalAmount\":100}"));
        consumer.consumeEstimateDeleted(message("ESTIMATE_DELETED",
                "{\"estimateId\":1,\"siteId\":2,\"totalAmount\":100}"));
        consumer.consumePurchaseRegistered(message("PURCHASE_REGISTERED",
                "{\"purchaseId\":3,\"siteId\":2,\"totalAmount\":40}"));
        consumer.consumePurchaseUpdated(message("PURCHASE_UPDATED",
                "{\"purchaseId\":3,\"siteId\":2,\"oldTotalAmount\":40,\"newTotalAmount\":50}"));
        consumer.consumePurchaseDeleted(message("PURCHASE_DELETED",
                "{\"purchaseId\":3,\"siteId\":2,\"totalAmount\":50}"));

        verify(profitService).applyEvent(EVENT_ID, ProfitEventType.ESTIMATE_PARSED,
                2L, new BigDecimal("100"), null);
        verify(profitService).applyEvent(EVENT_ID, ProfitEventType.ESTIMATE_DELETED,
                2L, new BigDecimal("100"), null);
        verify(profitService).applyEvent(EVENT_ID, ProfitEventType.PURCHASE_REGISTERED,
                2L, new BigDecimal("40"), null);
        verify(profitService).applyEvent(EVENT_ID, ProfitEventType.PURCHASE_UPDATED,
                2L, new BigDecimal("50"), new BigDecimal("40"));
        verify(profitService).applyEvent(EVENT_ID, ProfitEventType.PURCHASE_DELETED,
                2L, new BigDecimal("50"), null);
    }

    @Test
    void invalidJsonTypeAndRequiredFieldsAreNotAcknowledgedAsSuccess() {
        assertThrows(IllegalArgumentException.class, () -> consumer.consumeEstimateParsed("not-json"));
        assertThrows(IllegalArgumentException.class, () -> consumer.consumeEstimateParsed(
                message("PURCHASE_REGISTERED", "{\"estimateId\":1,\"siteId\":2,\"totalAmount\":100}")));
        assertThrows(IllegalArgumentException.class, () -> consumer.consumeEstimateParsed(
                message("ESTIMATE_PARSED", "{\"siteId\":2,\"totalAmount\":100}")));
        assertThrows(IllegalArgumentException.class, () -> consumer.consumePurchaseUpdated(
                message("PURCHASE_UPDATED", "{\"purchaseId\":3,\"siteId\":2,\"newTotalAmount\":50}")));
        assertThrows(IllegalArgumentException.class, () -> consumer.consumePurchaseDeleted(
                message("PURCHASE_DELETED", "{\"purchaseId\":3,\"siteId\":2,\"totalAmount\":-5}")));
        verifyNoInteractions(profitService);
    }

    @Test
    void domainFailurePropagatesToKafkaErrorHandler() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(profitService).applyEvent(eq(EVENT_ID), eq(ProfitEventType.PURCHASE_REGISTERED),
                        eq(2L), eq(new BigDecimal("40")), eq(null));

        assertThrows(IllegalStateException.class, () -> consumer.consumePurchaseRegistered(
                message("PURCHASE_REGISTERED", "{\"purchaseId\":3,\"siteId\":2,\"totalAmount\":40}")));
    }

    private String message(String type, String payload) {
        return "{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"" + type
                + "\",\"payload\":" + payload + "}";
    }
}
