package com.buildflow.notification.global.kafka;

import com.buildflow.notification.domain.notification.service.NotificationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KafkaConsumerService {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "estimate.parsed", groupId = "notification-service-group")
    public void consumeEstimateParsed(String message) {
        Event event = parse(message, "ESTIMATE_PARSED");
        notificationService.createNotification(event.eventId(), "ESTIMATE_PARSED",
                String.format("견적서(ID: %d)가 확정되었습니다.", requiredId(event.payload(), "estimateId")),
                requiredId(event.payload(), "siteId"));
    }

    @KafkaListener(topics = "purchase.registered", groupId = "notification-service-group")
    public void consumePurchaseRegistered(String message) {
        Event event = parse(message, "PURCHASE_REGISTERED");
        notificationService.createNotification(event.eventId(), "PURCHASE_REGISTERED",
                String.format("매입(ID: %d)이 등록되었습니다.", requiredId(event.payload(), "purchaseId")),
                requiredId(event.payload(), "siteId"));
    }

    @KafkaListener(topics = "tax.registered", groupId = "notification-service-group")
    public void consumeTaxRegistered(String message) {
        Event event = parse(message, "TAX_REGISTERED");
        String type = requiredText(event.payload(), "type");
        if (!"SALES".equals(type) && !"PURCHASE".equals(type)) {
            throw new IllegalArgumentException("유효하지 않은 세금계산서 구분입니다.");
        }
        notificationService.createNotification(event.eventId(), "TAX_REGISTERED",
                String.format("%s 세금계산서(ID: %d)가 등록되었습니다.",
                        "SALES".equals(type) ? "매출" : "매입", requiredId(event.payload(), "taxInvoiceId")),
                requiredId(event.payload(), "siteId"));
    }

    @KafkaListener(topics = "tax.payment.confirmed", groupId = "notification-service-group")
    public void consumeTaxPaymentConfirmed(String message) {
        Event event = parse(message, "TAX_PAYMENT_CONFIRMED");
        notificationService.createNotification(event.eventId(), "TAX_PAYMENT_CONFIRMED",
                String.format("세금계산서(ID: %d) 입금이 확인되었습니다.", requiredId(event.payload(), "taxInvoiceId")),
                requiredId(event.payload(), "siteId"));
    }

    @KafkaListener(topics = "estimate.deleted", groupId = "notification-service-group")
    public void consumeEstimateDeleted(String message) {
        Event event = parse(message, "ESTIMATE_DELETED");
        notificationService.createNotification(event.eventId(), "ESTIMATE_DELETED",
                String.format("견적서(ID: %d)가 삭제되었습니다.", requiredId(event.payload(), "estimateId")),
                requiredId(event.payload(), "siteId"));
    }

    @KafkaListener(topics = "purchase.updated", groupId = "notification-service-group")
    public void consumePurchaseUpdated(String message) {
        Event event = parse(message, "PURCHASE_UPDATED");
        notificationService.createNotification(event.eventId(), "PURCHASE_UPDATED",
                String.format("매입(ID: %d)이 수정되었습니다.", requiredId(event.payload(), "purchaseId")),
                requiredId(event.payload(), "siteId"));
    }

    @KafkaListener(topics = "purchase.deleted", groupId = "notification-service-group")
    public void consumePurchaseDeleted(String message) {
        Event event = parse(message, "PURCHASE_DELETED");
        notificationService.createNotification(event.eventId(), "PURCHASE_DELETED",
                String.format("매입(ID: %d)이 삭제되었습니다.", requiredId(event.payload(), "purchaseId")),
                requiredId(event.payload(), "siteId"));
    }

    @KafkaListener(topics = "warranty.expiring", groupId = "notification-service-group")
    public void consumeWarrantyExpiring(String message) {
        Event event = parse(message, "WARRANTY_EXPIRING");
        long daysUntilExpiry = requiredNonNegativeLong(event.payload(), "daysUntilExpiry");
        String insurer = optionalText(event.payload(), "insuranceCompany");
        notificationService.createNotification(event.eventId(), "WARRANTY_EXPIRING",
                String.format("하자보증보험(ID: %d, %s) 만료까지 %d일 남았습니다.",
                        requiredId(event.payload(), "warrantyId"), insurer, daysUntilExpiry),
                requiredId(event.payload(), "siteId"));
    }

    private Event parse(String message, String expectedType) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(message);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Kafka 이벤트 JSON을 읽을 수 없습니다.", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Kafka 이벤트 객체가 필요합니다.");
        }
        String eventId = requiredText(root, "eventId");
        if (eventId.length() != 36 || !eventId.equals(eventId.trim()) || !isCanonicalUuid(eventId)) {
            throw new IllegalArgumentException("유효하지 않은 eventId입니다.");
        }
        if (!expectedType.equals(requiredText(root, "eventType"))) {
            throw new IllegalArgumentException("토픽과 이벤트 종류가 일치하지 않습니다.");
        }
        JsonNode payload = root.get("payload");
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("Kafka 이벤트 payload 객체가 필요합니다.");
        }
        return new Event(eventId, payload);
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Kafka 이벤트 필수 필드가 없습니다: " + field);
        }
        return value.asText();
    }

    private boolean isCanonicalUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : "";
    }

    private Long requiredId(JsonNode node, String field) {
        long value = requiredLong(node, field);
        if (value <= 0) {
            throw new IllegalArgumentException("Kafka 이벤트 ID가 양수가 아닙니다: " + field);
        }
        return value;
    }

    private long requiredNonNegativeLong(JsonNode node, String field) {
        long value = requiredLong(node, field);
        if (value < 0) {
            throw new IllegalArgumentException("Kafka 이벤트 값이 음수입니다: " + field);
        }
        return value;
    }

    private long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("Kafka 이벤트 정수 필드가 없습니다: " + field);
        }
        return value.longValue();
    }

    private record Event(String eventId, JsonNode payload) {
    }
}
