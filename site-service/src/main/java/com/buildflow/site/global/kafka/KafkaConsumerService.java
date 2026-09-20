package com.buildflow.site.global.kafka;

import com.buildflow.site.domain.profit.event.ProfitEventType;
import com.buildflow.site.domain.profit.service.ProfitService;
import com.buildflow.site.global.event.KafkaEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class KafkaConsumerService {

    private final ProfitService profitService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "estimate.parsed", groupId = "site-service-group")
    public void consumeEstimateParsed(String message) {
        KafkaEvent<Map<String, Object>> event = readEvent(message, ProfitEventType.ESTIMATE_PARSED);
        Map<String, Object> payload = event.getPayload();
        requiredId(payload, "estimateId");
        profitService.applyEvent(event.getEventId(), ProfitEventType.ESTIMATE_PARSED,
                requiredId(payload, "siteId"), requiredAmount(payload, "totalAmount"), null);
    }

    @KafkaListener(topics = "estimate.deleted", groupId = "site-service-group")
    public void consumeEstimateDeleted(String message) {
        KafkaEvent<Map<String, Object>> event = readEvent(message, ProfitEventType.ESTIMATE_DELETED);
        Map<String, Object> payload = event.getPayload();
        requiredId(payload, "estimateId");
        profitService.applyEvent(event.getEventId(), ProfitEventType.ESTIMATE_DELETED,
                requiredId(payload, "siteId"), requiredAmount(payload, "totalAmount"), null);
    }

    @KafkaListener(topics = "purchase.registered", groupId = "site-service-group")
    public void consumePurchaseRegistered(String message) {
        KafkaEvent<Map<String, Object>> event = readEvent(message, ProfitEventType.PURCHASE_REGISTERED);
        Map<String, Object> payload = event.getPayload();
        requiredId(payload, "purchaseId");
        profitService.applyEvent(event.getEventId(), ProfitEventType.PURCHASE_REGISTERED,
                requiredId(payload, "siteId"), requiredAmount(payload, "totalAmount"), null);
    }

    @KafkaListener(topics = "purchase.updated", groupId = "site-service-group")
    public void consumePurchaseUpdated(String message) {
        KafkaEvent<Map<String, Object>> event = readEvent(message, ProfitEventType.PURCHASE_UPDATED);
        Map<String, Object> payload = event.getPayload();
        requiredId(payload, "purchaseId");
        profitService.applyEvent(event.getEventId(), ProfitEventType.PURCHASE_UPDATED,
                requiredId(payload, "siteId"), requiredAmount(payload, "newTotalAmount"),
                requiredAmount(payload, "oldTotalAmount"));
    }

    @KafkaListener(topics = "purchase.deleted", groupId = "site-service-group")
    public void consumePurchaseDeleted(String message) {
        KafkaEvent<Map<String, Object>> event = readEvent(message, ProfitEventType.PURCHASE_DELETED);
        Map<String, Object> payload = event.getPayload();
        requiredId(payload, "purchaseId");
        profitService.applyEvent(event.getEventId(), ProfitEventType.PURCHASE_DELETED,
                requiredId(payload, "siteId"), requiredAmount(payload, "totalAmount"), null);
    }

    private KafkaEvent<Map<String, Object>> readEvent(String message, ProfitEventType expectedType) {
        try {
            KafkaEvent<Map<String, Object>> event = objectMapper.readValue(
                    message, new TypeReference<>() {});
            if (event == null || event.getPayload() == null
                    || !expectedType.name().equals(event.getEventType())) {
                throw new IllegalArgumentException("Kafka 손익 이벤트 형식 또는 유형이 잘못되었습니다: " + expectedType);
            }
            return event;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Kafka 손익 이벤트 JSON을 읽을 수 없습니다", e);
        }
    }

    private Long requiredId(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        try {
            long id = Long.parseLong(String.valueOf(value));
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // Report a stable validation error to the Kafka error handler.
        }
        throw new IllegalArgumentException("Kafka 손익 이벤트에 유효한 " + field + "가 없습니다");
    }

    private BigDecimal requiredAmount(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        try {
            BigDecimal amount = new BigDecimal(String.valueOf(value));
            if (amount.signum() >= 0) {
                return amount;
            }
        } catch (NumberFormatException ignored) {
            // Report a stable validation error to the Kafka error handler.
        }
        throw new IllegalArgumentException("Kafka 손익 이벤트에 유효한 " + field + "가 없습니다");
    }
}
