package com.buildflow.purchase.global.kafka;

import com.buildflow.purchase.domain.purchase.event.PurchaseRegisteredPayload;
import com.buildflow.purchase.domain.purchase.event.PurchaseUpdatedPayload;
import com.buildflow.purchase.global.event.KafkaEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaProducerService {

    private static final String TOPIC_PURCHASE_REGISTERED = "purchase.registered";
    private static final String TOPIC_PURCHASE_UPDATED = "purchase.updated";
    private static final String TOPIC_PURCHASE_DELETED = "purchase.deleted";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void sendPurchaseRegistered(PurchaseRegisteredPayload payload) {
        KafkaEvent<PurchaseRegisteredPayload> event = KafkaEvent.of("PURCHASE_REGISTERED", payload);
        kafkaTemplate.send(TOPIC_PURCHASE_REGISTERED, String.valueOf(payload.getPurchaseId()), event);
        log.info("Kafka 발행: {} purchaseId={}, siteId={}", TOPIC_PURCHASE_REGISTERED, payload.getPurchaseId(), payload.getSiteId());
    }

    public void sendPurchaseUpdated(PurchaseUpdatedPayload payload) {
        KafkaEvent<PurchaseUpdatedPayload> event = KafkaEvent.of("PURCHASE_UPDATED", payload);
        kafkaTemplate.send(TOPIC_PURCHASE_UPDATED, String.valueOf(payload.getPurchaseId()), event);
        log.info("Kafka 발행: {} purchaseId={}, siteId={}", TOPIC_PURCHASE_UPDATED, payload.getPurchaseId(), payload.getSiteId());
    }

    public void sendPurchaseDeleted(PurchaseRegisteredPayload payload) {
        KafkaEvent<PurchaseRegisteredPayload> event = KafkaEvent.of("PURCHASE_DELETED", payload);
        kafkaTemplate.send(TOPIC_PURCHASE_DELETED, String.valueOf(payload.getPurchaseId()), event);
        log.info("Kafka 발행: {} purchaseId={}, siteId={}", TOPIC_PURCHASE_DELETED, payload.getPurchaseId(), payload.getSiteId());
    }
}
