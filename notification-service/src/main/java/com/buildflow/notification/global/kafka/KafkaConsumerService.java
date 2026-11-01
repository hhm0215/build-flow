package com.buildflow.notification.global.kafka;

import com.buildflow.notification.domain.notification.service.NotificationMessageFormatter;
import com.buildflow.notification.domain.notification.service.NotificationService;
import com.buildflow.notification.domain.notification.service.SiteNameResolver;
import com.buildflow.notification.domain.warranty.service.WarrantyExpiryNotificationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KafkaConsumerService {

    private final NotificationService notificationService;
    private final WarrantyExpiryNotificationService warrantyExpiryNotificationService;
    private final ObjectMapper objectMapper;
    private final SiteNameResolver siteNameResolver;

    @KafkaListener(topics = "estimate.parsed", groupId = "notification-service-group")
    public void consumeEstimateParsed(String message) {
        Event event = parse(message, "ESTIMATE_PARSED");
        Long estimateId = requiredId(event.payload(), "estimateId");
        Long siteId = requiredId(event.payload(), "siteId");
        notificationService.createNotification(event.eventId(), "ESTIMATE_PARSED",
                NotificationMessageFormatter.estimateConfirmed(siteNameResolver.resolve(siteId), estimateId,
                        displayText(event.payload(), "title")),
                siteId);
    }

    @KafkaListener(topics = "purchase.registered", groupId = "notification-service-group")
    public void consumePurchaseRegistered(String message) {
        consumePurchase(parse(message, "PURCHASE_REGISTERED"), "PURCHASE_REGISTERED", "등록");
    }

    @KafkaListener(topics = "tax.registered", groupId = "notification-service-group")
    public void consumeTaxRegistered(String message) {
        Event event = parse(message, "TAX_REGISTERED");
        String type = requiredText(event.payload(), "type");
        if (!"SALES".equals(type) && !"PURCHASE".equals(type)) {
            throw new IllegalArgumentException("유효하지 않은 세금계산서 구분입니다.");
        }
        Long taxInvoiceId = requiredId(event.payload(), "taxInvoiceId");
        Long siteId = requiredId(event.payload(), "siteId");
        notificationService.createNotification(event.eventId(), "TAX_REGISTERED",
                NotificationMessageFormatter.taxRegistered(siteNameResolver.resolve(siteId), "SALES".equals(type),
                        taxInvoiceId, displayText(event.payload(), "counterparty"),
                        displayAmount(event.payload(), "totalAmount")),
                siteId);
    }

    @KafkaListener(topics = "tax.payment.confirmed", groupId = "notification-service-group")
    public void consumeTaxPaymentConfirmed(String message) {
        Event event = parse(message, "TAX_PAYMENT_CONFIRMED");
        Long taxInvoiceId = requiredId(event.payload(), "taxInvoiceId");
        Long siteId = requiredId(event.payload(), "siteId");
        notificationService.createNotification(event.eventId(), "TAX_PAYMENT_CONFIRMED",
                NotificationMessageFormatter.taxPaymentConfirmed(siteNameResolver.resolve(siteId), taxInvoiceId,
                        displayText(event.payload(), "counterparty"),
                        displayAmount(event.payload(), "totalAmount")),
                siteId);
    }

    @KafkaListener(topics = "estimate.deleted", groupId = "notification-service-group")
    public void consumeEstimateDeleted(String message) {
        Event event = parse(message, "ESTIMATE_DELETED");
        Long estimateId = requiredId(event.payload(), "estimateId");
        Long siteId = requiredId(event.payload(), "siteId");
        boolean superseded = "SUPERSEDED".equals(optionalText(event.payload(), "reason"));
        Long supersededById = superseded ? requiredId(event.payload(), "supersededById") : null;
        String siteName = siteNameResolver.resolve(siteId);
        String title = displayText(event.payload(), "title");
        String content = superseded
                ? NotificationMessageFormatter.estimateSuperseded(siteName, estimateId, title, supersededById)
                : NotificationMessageFormatter.estimateDeleted(siteName, estimateId, title);
        notificationService.createNotification(event.eventId(), "ESTIMATE_DELETED", content, siteId);
    }

    @KafkaListener(topics = "purchase.updated", groupId = "notification-service-group")
    public void consumePurchaseUpdated(String message) {
        consumePurchase(parse(message, "PURCHASE_UPDATED"), "PURCHASE_UPDATED", "수정");
    }

    @KafkaListener(topics = "purchase.deleted", groupId = "notification-service-group")
    public void consumePurchaseDeleted(String message) {
        consumePurchase(parse(message, "PURCHASE_DELETED"), "PURCHASE_DELETED", "삭제");
    }

    @KafkaListener(topics = "warranty.expiring", groupId = "notification-service-group")
    public void consumeWarrantyExpiring(String message) {
        Event event = parse(message, "WARRANTY_EXPIRING");
        requiredNonNegativeLong(event.payload(), "daysUntilExpiry");
        Long warrantyId = requiredId(event.payload(), "warrantyId");
        Long siteId = requiredId(event.payload(), "siteId");
        String insuranceCompany = requiredText(event.payload(), "insuranceCompany");
        LocalDate endDate = requiredDate(event.payload(), "endDate");
        String expiryConfirmationRef = optionalText(event.payload(), "expiryConfirmationRef");
        // 현장명 조회는 행 잠금·트랜잭션 밖에서 끝낸다
        warrantyExpiryNotificationService.consume(event.eventId(), warrantyId, siteId,
                insuranceCompany, endDate, expiryConfirmationRef, siteNameResolver.resolve(siteId));
    }

    private void consumePurchase(Event event, String eventType, String action) {
        Long purchaseId = requiredId(event.payload(), "purchaseId");
        Long siteId = requiredId(event.payload(), "siteId");
        notificationService.createNotification(event.eventId(), eventType,
                NotificationMessageFormatter.purchase(action, siteNameResolver.resolve(siteId), purchaseId,
                        displayText(event.payload(), "itemName"), displayText(event.payload(), "supplier")),
                siteId);
    }

    /** 알림 문구 보강용 선택 필드. 없거나 형식이 달라도(구 이벤트) 소비를 실패시키지 않고 null로 폴백한다. */
    private String displayText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private BigDecimal displayAmount(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            return null;
        }
        return value.decimalValue();
    }

    private Event parse(String message, String expectedType) {
        final JsonNode root;
        try {
            // 금액 표시가 double 반올림을 거치지 않도록 실수는 BigDecimal로 읽는다
            root = objectMapper.reader()
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readTree(message);
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

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null; // legacy event: ignore with receipt
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Kafka 이벤트 필드가 유효하지 않습니다: " + field);
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

    private LocalDate requiredDate(JsonNode node, String field) {
        try {
            return LocalDate.parse(requiredText(node, field));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Kafka 이벤트 날짜 필드가 유효하지 않습니다: " + field, e);
        }
    }

    private record Event(String eventId, JsonNode payload) {
    }
}
