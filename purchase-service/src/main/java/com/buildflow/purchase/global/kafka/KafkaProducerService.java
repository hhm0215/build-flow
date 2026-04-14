package com.buildflow.purchase.global.kafka;

import com.buildflow.purchase.domain.purchase.event.PurchaseRegisteredPayload;
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

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void sendPurchaseRegistered(PurchaseRegisteredPayload payload) {
        KafkaEvent<PurchaseRegisteredPayload> event = KafkaEvent.of("PURCHASE_REGISTERED", payload);
        kafkaTemplate.send(TOPIC_PURCHASE_REGISTERED, String.valueOf(payload.getPurchaseId()), event);
        log.info("Kafka 발행: {} purchaseId={}, siteId={}", TOPIC_PURCHASE_REGISTERED, payload.getPurchaseId(), payload.getSiteId());
    }
}
