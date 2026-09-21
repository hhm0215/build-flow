package com.buildflow.notification.global.kafka;

import com.buildflow.notification.domain.warranty.event.WarrantyExpiringPayload;
import com.buildflow.notification.global.event.KafkaEvent;
import com.buildflow.notification.global.outbox.OutboxEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaProducerService {

    private static final String TOPIC_WARRANTY_EXPIRING = "warranty.expiring";

    private final OutboxEventService outboxEventService;

    /**
     * Caller transaction commits the event and warranty cooldown marker together.
     */
    public void sendWarrantyExpiring(WarrantyExpiringPayload payload) {
        KafkaEvent<WarrantyExpiringPayload> event = KafkaEvent.of("WARRANTY_EXPIRING", payload);
        outboxEventService.enqueue(TOPIC_WARRANTY_EXPIRING,
                String.valueOf(payload.getWarrantyId()), event);
        log.info("Kafka outbox 등록: {} warrantyId={}, eventId={}",
                TOPIC_WARRANTY_EXPIRING, payload.getWarrantyId(), event.getEventId());
    }
}
