package com.buildflow.site.global.kafka;

import com.buildflow.site.domain.profit.event.EstimateParsedPayload;
import com.buildflow.site.domain.profit.service.ProfitService;
import com.buildflow.site.global.event.KafkaEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaConsumerService {

    private final ProfitService profitService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "estimate.parsed", groupId = "site-service-group")
    public void consumeEstimateParsed(String message) {
        try {
            KafkaEvent<EstimateParsedPayload> event = objectMapper.readValue(
                    message, new TypeReference<>() {}
            );

            EstimateParsedPayload payload = event.getPayload();
            log.info("Kafka 수신: estimate.parsed eventId={}, estimateId={}, siteId={}",
                    event.getEventId(), payload.getEstimateId(), payload.getSiteId());

            if (payload.getSiteId() != null && payload.getTotalAmount() != null) {
                profitService.addEstimateAmount(payload.getSiteId(), payload.getTotalAmount());
            } else {
                log.warn("estimate.parsed 이벤트에 siteId 또는 totalAmount 누락: eventId={}", event.getEventId());
            }
        } catch (Exception e) {
            log.error("estimate.parsed 처리 실패: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "purchase.registered", groupId = "site-service-group")
    public void consumePurchaseRegistered(String message) {
        try {
            KafkaEvent<java.util.Map<String, Object>> event = objectMapper.readValue(
                    message, new TypeReference<>() {}
            );

            java.util.Map<String, Object> payload = event.getPayload();
            log.info("Kafka 수신: purchase.registered eventId={}", event.getEventId());

            Long siteId = payload.get("siteId") != null
                    ? Long.valueOf(payload.get("siteId").toString()) : null;
            java.math.BigDecimal totalAmount = payload.get("totalAmount") != null
                    ? new java.math.BigDecimal(payload.get("totalAmount").toString()) : null;

            if (siteId != null && totalAmount != null) {
                profitService.addPurchaseAmount(siteId, totalAmount);
            } else {
                log.warn("purchase.registered 이벤트에 siteId 또는 totalAmount 누락: eventId={}", event.getEventId());
            }
        } catch (Exception e) {
            log.error("purchase.registered 처리 실패: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "estimate.deleted", groupId = "site-service-group")
    public void consumeEstimateDeleted(String message) {
        try {
            KafkaEvent<EstimateParsedPayload> event = objectMapper.readValue(
                    message, new TypeReference<>() {}
            );

            EstimateParsedPayload payload = event.getPayload();
            log.info("Kafka 수신: estimate.deleted eventId={}, estimateId={}, siteId={}",
                    event.getEventId(), payload.getEstimateId(), payload.getSiteId());

            if (payload.getSiteId() != null && payload.getTotalAmount() != null) {
                profitService.subtractEstimateAmount(payload.getSiteId(), payload.getTotalAmount());
            }
        } catch (Exception e) {
            log.error("estimate.deleted 처리 실패: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "purchase.updated", groupId = "site-service-group")
    public void consumePurchaseUpdated(String message) {
        try {
            KafkaEvent<java.util.Map<String, Object>> event = objectMapper.readValue(
                    message, new TypeReference<>() {}
            );

            java.util.Map<String, Object> payload = event.getPayload();
            log.info("Kafka 수신: purchase.updated eventId={}", event.getEventId());

            Long siteId = payload.get("siteId") != null
                    ? Long.valueOf(payload.get("siteId").toString()) : null;
            java.math.BigDecimal oldAmount = payload.get("oldTotalAmount") != null
                    ? new java.math.BigDecimal(payload.get("oldTotalAmount").toString()) : null;
            java.math.BigDecimal newAmount = payload.get("newTotalAmount") != null
                    ? new java.math.BigDecimal(payload.get("newTotalAmount").toString()) : null;

            if (siteId != null && oldAmount != null && newAmount != null) {
                profitService.updatePurchaseAmount(siteId, oldAmount, newAmount);
            }
        } catch (Exception e) {
            log.error("purchase.updated 처리 실패: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "purchase.deleted", groupId = "site-service-group")
    public void consumePurchaseDeleted(String message) {
        try {
            KafkaEvent<java.util.Map<String, Object>> event = objectMapper.readValue(
                    message, new TypeReference<>() {}
            );

            java.util.Map<String, Object> payload = event.getPayload();
            log.info("Kafka 수신: purchase.deleted eventId={}", event.getEventId());

            Long siteId = payload.get("siteId") != null
                    ? Long.valueOf(payload.get("siteId").toString()) : null;
            java.math.BigDecimal totalAmount = payload.get("totalAmount") != null
                    ? new java.math.BigDecimal(payload.get("totalAmount").toString()) : null;

            if (siteId != null && totalAmount != null) {
                profitService.subtractPurchaseAmount(siteId, totalAmount);
            }
        } catch (Exception e) {
            log.error("purchase.deleted 처리 실패: {}", e.getMessage(), e);
        }
    }
}
