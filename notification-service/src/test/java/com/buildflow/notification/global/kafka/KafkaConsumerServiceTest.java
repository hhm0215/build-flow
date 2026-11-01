package com.buildflow.notification.global.kafka;

import com.buildflow.notification.domain.notification.service.NotificationService;
import com.buildflow.notification.domain.notification.service.SiteNameResolver;
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
    private final SiteNameResolver siteNameResolver = mock(SiteNameResolver.class);
    private final KafkaConsumerService consumer = new KafkaConsumerService(
            notificationService, warrantyExpiryNotificationService, new ObjectMapper(), siteNameResolver);

    @Test
    void validEventPassesEventIdToTransactionalService() {
        consumer.consumeEstimateParsed(event("ESTIMATE_PARSED", "\"siteId\":7,\"estimateId\":11"));

        verify(notificationService).createNotification(EVENT_ID, "ESTIMATE_PARSED",
                "견적서(ID: 11)가 확정되었습니다.", 7L);
    }

    @Test
    void supersededEstimateDeletionUsesReplacementMessage() {
        consumer.consumeEstimateDeleted(event("ESTIMATE_DELETED",
                "\"siteId\":7,\"estimateId\":11,\"totalAmount\":1000"));
        consumer.consumeEstimateDeleted(event("ESTIMATE_DELETED",
                "\"siteId\":7,\"estimateId\":11,\"totalAmount\":1000,"
                        + "\"reason\":\"SUPERSEDED\",\"supersededById\":12"));

        verify(notificationService).createNotification(EVENT_ID, "ESTIMATE_DELETED",
                "견적서(ID: 11)가 삭제되었습니다.", 7L);
        verify(notificationService).createNotification(EVENT_ID, "ESTIMATE_DELETED",
                "견적서(ID: 11)가 수정본(ID: 12) 확정으로 대체되었습니다.", 7L);
        assertThatThrownBy(() -> consumer.consumeEstimateDeleted(event("ESTIMATE_DELETED",
                "\"siteId\":7,\"estimateId\":11,\"reason\":\"SUPERSEDED\"")))
                .isInstanceOf(IllegalArgumentException.class);
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
                        + "\"endDate\":\"2026-11-01\",\"daysUntilExpiry\":18,"
                        + "\"expiryConfirmationRef\":\"2026-10-14T00:00:00Z\""));

        verify(warrantyExpiryNotificationService).consume(EVENT_ID, 5L, 7L,
                "보험사", java.time.LocalDate.of(2026, 11, 1), "2026-10-14T00:00:00Z", null);
        verifyNoInteractions(notificationService);
    }

    @Test
    void legacyWarrantyEventWithoutConfirmationRefIsPassedForIgnoredReceipt() {
        consumer.consumeWarrantyExpiring(event("WARRANTY_EXPIRING",
                "\"warrantyId\":5,\"siteId\":7,\"insuranceCompany\":\"보험사\","
                        + "\"endDate\":\"2026-11-01\",\"daysUntilExpiry\":18"));

        verify(warrantyExpiryNotificationService).consume(EVENT_ID, 5L, 7L,
                "보험사", java.time.LocalDate.of(2026, 11, 1), null, null);
    }

    @Test
    void malformedWarrantySnapshotIsNotAcknowledged() {
        assertThatThrownBy(() -> consumer.consumeWarrantyExpiring(event("WARRANTY_EXPIRING",
                "\"warrantyId\":5,\"siteId\":7,\"insuranceCompany\":\"보험사\","
                        + "\"endDate\":\"bad-date\",\"daysUntilExpiry\":18")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(warrantyExpiryNotificationService);
    }

    @Test
    void namedEventsProduceHumanReadableMessages() {
        when(siteNameResolver.resolve(7L)).thenReturn("강남 A현장");

        consumer.consumeEstimateParsed(event("ESTIMATE_PARSED",
                "\"siteId\":7,\"estimateId\":11,\"totalAmount\":1000,\"title\":\"외벽 도장 공사\""));
        consumer.consumePurchaseRegistered(event("PURCHASE_REGISTERED",
                "\"siteId\":7,\"purchaseId\":2,\"revision\":1,\"totalAmount\":5000,"
                        + "\"itemName\":\"시멘트\",\"supplier\":\"대한건자재\""));
        consumer.consumePurchaseUpdated(event("PURCHASE_UPDATED",
                "\"siteId\":7,\"purchaseId\":2,\"itemName\":\"시멘트\""));
        consumer.consumeTaxRegistered(event("TAX_REGISTERED",
                "\"siteId\":7,\"taxInvoiceId\":3,\"type\":\"SALES\","
                        + "\"totalAmount\":1100000.00,\"counterparty\":\"대한건설\""));
        consumer.consumeTaxPaymentConfirmed(event("TAX_PAYMENT_CONFIRMED",
                "\"siteId\":7,\"taxInvoiceId\":3,\"type\":\"SALES\","
                        + "\"totalAmount\":9999999999999.99,\"counterparty\":null"));

        verify(notificationService).createNotification(EVENT_ID, "ESTIMATE_PARSED",
                "[강남 A현장] 견적서(외벽 도장 공사)가 확정되었습니다.", 7L);
        verify(notificationService).createNotification(EVENT_ID, "PURCHASE_REGISTERED",
                "[강남 A현장] 매입(시멘트, 대한건자재)이 등록되었습니다.", 7L);
        verify(notificationService).createNotification(EVENT_ID, "PURCHASE_UPDATED",
                "[강남 A현장] 매입(시멘트)이 수정되었습니다.", 7L);
        verify(notificationService).createNotification(EVENT_ID, "TAX_REGISTERED",
                "[강남 A현장] 매출 세금계산서(대한건설, 1,100,000원)가 등록되었습니다.", 7L);
        // double을 거치면 16자리 금액이 반올림된다 — BigDecimal로 읽어 그대로 표시
        verify(notificationService).createNotification(EVENT_ID, "TAX_PAYMENT_CONFIRMED",
                "[강남 A현장] 세금계산서(9,999,999,999,999.99원) 입금이 확인되었습니다.", 7L);
    }

    @Test
    void legacyEventsWithoutNamesFallBackToIdMessages() {
        // 구 이벤트(이름 필드 없음) + 현장명 조회 실패(null) → 기존 ID 문구
        consumer.consumePurchaseDeleted(event("PURCHASE_DELETED",
                "\"siteId\":7,\"purchaseId\":2,\"revision\":3,\"totalAmount\":5000"));
        consumer.consumeTaxRegistered(event("TAX_REGISTERED",
                "\"siteId\":7,\"taxInvoiceId\":3,\"type\":\"PURCHASE\""));
        // 이름 필드의 형식이 틀려도 소비는 실패하지 않는다
        consumer.consumeEstimateParsed(event("ESTIMATE_PARSED",
                "\"siteId\":7,\"estimateId\":11,\"title\":123"));

        verify(notificationService).createNotification(EVENT_ID, "PURCHASE_DELETED",
                "매입(ID: 2)이 삭제되었습니다.", 7L);
        verify(notificationService).createNotification(EVENT_ID, "TAX_REGISTERED",
                "매입 세금계산서(ID: 3)가 등록되었습니다.", 7L);
        verify(notificationService).createNotification(EVENT_ID, "ESTIMATE_PARSED",
                "견적서(ID: 11)가 확정되었습니다.", 7L);
    }

    @Test
    void invalidEventsDoNotTriggerSiteLookup() {
        assertThatThrownBy(() -> consumer.consumeEstimateParsed(
                event("ESTIMATE_PARSED", "\"siteId\":7")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(siteNameResolver);
    }

    @Test
    void warrantyEventPassesResolvedSiteName() {
        when(siteNameResolver.resolve(7L)).thenReturn("강남 A현장");
        consumer.consumeWarrantyExpiring(event("WARRANTY_EXPIRING",
                "\"warrantyId\":5,\"siteId\":7,\"insuranceCompany\":\"보험사\","
                        + "\"endDate\":\"2026-11-01\",\"daysUntilExpiry\":18,"
                        + "\"expiryConfirmationRef\":\"2026-10-14T00:00:00Z\""));

        verify(warrantyExpiryNotificationService).consume(EVENT_ID, 5L, 7L,
                "보험사", java.time.LocalDate.of(2026, 11, 1), "2026-10-14T00:00:00Z", "강남 A현장");
    }

    private String event(String type, String payloadFields) {
        return "{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"" + type
                + "\",\"payload\":{" + payloadFields + "}}";
    }
}
