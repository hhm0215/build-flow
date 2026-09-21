package com.buildflow.notification.global.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class OutboxPublisher {

    private final OutboxClaimService claimService;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxPublisher(OutboxClaimService claimService,
                           @Qualifier("outboxKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        this.claimService = claimService;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-delay-ms:1000}")
    public void publishReady() {
        for (int i = 0; i < 100; i++) {
            OutboxClaim claim;
            try {
                claim = claimService.claimNext().orElse(null);
            } catch (Exception e) {
                log.error("Outbox claim 실패", e);
                return;
            }
            if (claim == null) return;

            try {
                kafkaTemplate.send(claim.topic(), claim.recordKey(), claim.payloadJson())
                        .get(5, TimeUnit.SECONDS);
                if (!claimService.markSent(claim)) {
                    log.warn("Outbox claim이 만료되어 SENT 갱신 생략: eventId={}", claim.eventId());
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                try {
                    if (!claimService.markFailed(claim, e.toString())) {
                        log.warn("Outbox claim이 만료되어 실패 갱신 생략: eventId={}", claim.eventId());
                    }
                } catch (Exception updateError) {
                    log.error("Outbox 실패 상태 저장 실패: eventId={}", claim.eventId(), updateError);
                    return;
                }
                log.error("Outbox 발행 실패, 재시도 예정: eventId={} topic={}",
                        claim.eventId(), claim.topic(), e);
                if (Thread.currentThread().isInterrupted()) return;
            }
        }
    }
}
