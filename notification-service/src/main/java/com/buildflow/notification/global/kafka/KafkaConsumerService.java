package com.buildflow.notification.global.kafka;

import com.buildflow.notification.domain.notification.service.NotificationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaConsumerService {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "estimate.parsed", groupId = "notification-service-group")
    public void consumeEstimateParsed(String message) {
        try {
            Map<String, Object> event = objectMapper.readValue(message, new TypeReference<>() {});
            Map<String, Object> payload = getPayload(event);

            Long siteId = toLong(payload.get("siteId"));
            Long estimateId = toLong(payload.get("estimateId"));

            notificationService.createNotification(
                    "ESTIMATE_PARSED",
                    String.format("견적서(ID: %d)가 확정되었습니다.", estimateId),
                    siteId
            );
        } catch (Exception e) {
            log.error("estimate.parsed 알림 처리 실패: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "purchase.registered", groupId = "notification-service-group")
    public void consumePurchaseRegistered(String message) {
        try {
            Map<String, Object> event = objectMapper.readValue(message, new TypeReference<>() {});
            Map<String, Object> payload = getPayload(event);

            Long siteId = toLong(payload.get("siteId"));
            Long purchaseId = toLong(payload.get("purchaseId"));

            notificationService.createNotification(
                    "PURCHASE_REGISTERED",
                    String.format("매입(ID: %d)이 등록되었습니다.", purchaseId),
                    siteId
            );
        } catch (Exception e) {
            log.error("purchase.registered 알림 처리 실패: {}", e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getPayload(Map<String, Object> event) {
        return (Map<String, Object>) event.get("payload");
    }

    private Long toLong(Object value) {
        return value != null ? Long.valueOf(value.toString()) : null;
    }
}
