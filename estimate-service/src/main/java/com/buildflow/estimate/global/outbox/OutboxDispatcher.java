package com.buildflow.estimate.global.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxDispatcher {

    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final OutboxClaimService claimService;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${app.outbox.batch-size:100}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${app.outbox.poll-delay-ms:1000}")
    public void dispatch() {
        int limit = Math.max(1, Math.min(batchSize, 100));
        for (int i = 0; i < limit; i++) {
            Optional<OutboxClaim> next = claimService.claimNext();
            if (next.isEmpty()) {
                return;
            }
            OutboxClaim claim = next.get();
            try {
                kafkaTemplate.send(claim.topic(), claim.recordKey(), claim.payloadJson())
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (claimService.markSent(claim)) {
                    log.info("Kafka outbox 발행 ACK: topic={}, eventId={}", claim.topic(), claim.eventId());
                }
            } catch (InterruptedException e) {
                try {
                    claimService.markFailed(claim, e);
                } finally {
                    Thread.currentThread().interrupt();
                }
                return;
            } catch (Exception e) {
                try {
                    claimService.markFailed(claim, e);
                } catch (Exception stateError) {
                    log.error("outbox 실패 상태 기록 실패, lease 만료 후 재시도: eventId={}",
                            claim.eventId(), stateError);
                }
                log.error("Kafka outbox 발행 실패: topic={}, eventId={}",
                        claim.topic(), claim.eventId(), e);
                return;
            }
        }
    }
}
