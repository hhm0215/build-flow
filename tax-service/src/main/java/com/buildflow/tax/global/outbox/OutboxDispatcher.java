package com.buildflow.tax.global.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDispatcher {

    private static final int MAX_BATCH_SIZE = 100;
    private static final long ACK_TIMEOUT_SECONDS = 5;

    private final OutboxClaimService claimService;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelayString = "${app.outbox.poll-delay-ms:1000}")
    public void dispatch() {
        for (int i = 0; i < MAX_BATCH_SIZE; i++) {
            Optional<OutboxClaim> next = claimService.claimNext(LocalDateTime.now());
            if (next.isEmpty()) {
                return;
            }
            OutboxClaim claim = next.orElseThrow();
            try {
                kafkaTemplate.send(claim.topic(), claim.recordKey(), claim.payloadJson())
                        .get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!claimService.markSent(claim, LocalDateTime.now())) {
                    log.warn("세금 outbox ACK 이후 소유권 변경: eventId={}", claim.eventId());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("세금 outbox dispatcher 중단: eventId={}", claim.eventId(), e);
                return;
            } catch (Exception e) {
                log.error("세금 outbox 발행 실패: eventId={}", claim.eventId(), e);
                claimService.markFailed(claim, LocalDateTime.now(), e);
            }
        }
    }
}
