package com.buildflow.notification.global.kafka;

import com.buildflow.notification.domain.warranty.event.WarrantyExpiringPayload;
import com.buildflow.notification.global.event.KafkaEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaProducerService {

    private static final String TOPIC_WARRANTY_EXPIRING = "warranty.expiring";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * warranty.expiring 토픽 발행.
     *
     * <p>CompletableFuture 반환 — 호출자(스케줄러)가 .get() 동기 대기로 broker 실패를 감지.
     * 발행 실패 시 markExpiringAlertSent 호출하지 않아 다음 cron에서 재시도 가능.
     */
    public CompletableFuture<SendResult<String, Object>> sendWarrantyExpiring(WarrantyExpiringPayload payload) {
        KafkaEvent<WarrantyExpiringPayload> event = KafkaEvent.of("WARRANTY_EXPIRING", payload);
        CompletableFuture<SendResult<String, Object>> future = kafkaTemplate.send(
                TOPIC_WARRANTY_EXPIRING, String.valueOf(payload.getWarrantyId()), event);
        log.info("Kafka 발행 요청: {} warrantyId={}, siteId={}, daysUntilExpiry={}",
                TOPIC_WARRANTY_EXPIRING, payload.getWarrantyId(), payload.getSiteId(), payload.getDaysUntilExpiry());
        return future;
    }
}
