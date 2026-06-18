package com.buildflow.notification.global.kafka;

import com.buildflow.notification.domain.warranty.event.WarrantyExpiringPayload;
import com.buildflow.notification.global.event.KafkaEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaProducerService {

    private static final String TOPIC_WARRANTY_EXPIRING = "warranty.expiring";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void sendWarrantyExpiring(WarrantyExpiringPayload payload) {
        KafkaEvent<WarrantyExpiringPayload> event = KafkaEvent.of("WARRANTY_EXPIRING", payload);
        kafkaTemplate.send(TOPIC_WARRANTY_EXPIRING, String.valueOf(payload.getWarrantyId()), event);
        log.info("Kafka 발행: {} warrantyId={}, siteId={}, daysUntilExpiry={}",
                TOPIC_WARRANTY_EXPIRING, payload.getWarrantyId(), payload.getSiteId(), payload.getDaysUntilExpiry());
    }
}
