package com.buildflow.purchase.global.kafka;

import com.buildflow.purchase.domain.purchase.event.PurchaseRegisteredPayload;
import com.buildflow.purchase.domain.purchase.event.PurchaseUpdatedPayload;
import com.buildflow.purchase.global.event.KafkaEvent;
import com.buildflow.purchase.global.outbox.OutboxEvent;
import com.buildflow.purchase.global.outbox.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class KafkaProducerService {

    private static final String TOPIC_PURCHASE_REGISTERED = "purchase.registered";
    private static final String TOPIC_PURCHASE_UPDATED = "purchase.updated";
    private static final String TOPIC_PURCHASE_DELETED = "purchase.deleted";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void sendPurchaseRegistered(PurchaseRegisteredPayload payload) {
        enqueue(TOPIC_PURCHASE_REGISTERED, payload.getPurchaseId(),
                KafkaEvent.of("PURCHASE_REGISTERED", payload));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void sendPurchaseUpdated(PurchaseUpdatedPayload payload) {
        enqueue(TOPIC_PURCHASE_UPDATED, payload.getPurchaseId(),
                KafkaEvent.of("PURCHASE_UPDATED", payload));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void sendPurchaseDeleted(PurchaseRegisteredPayload payload) {
        enqueue(TOPIC_PURCHASE_DELETED, payload.getPurchaseId(),
                KafkaEvent.of("PURCHASE_DELETED", payload));
    }

    private void enqueue(String topic, Long purchaseId, KafkaEvent<?> event) {
        try {
            String payloadJson = objectMapper.writeValueAsString(event);
            outboxEventRepository.save(new OutboxEvent(
                    event.getEventId(), topic, String.valueOf(purchaseId), payloadJson));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("매입 이벤트 직렬화에 실패했습니다.", e);
        }
    }
}
