package com.buildflow.purchase.global.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class OutboxDispatcher {

    private static final int MAX_PER_POLL = 100;
    private static final long ACK_TIMEOUT_SECONDS = 5;

    private final OutboxClaimService claimService;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxDispatcher(OutboxClaimService claimService,
                            @Qualifier("outboxKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        this.claimService = claimService;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    public void dispatchPending() {
        for (int i = 0; i < MAX_PER_POLL && dispatchOnce(); i++) {
            // 한 번에 처리하는 건수를 제한해 장애 시 DB/Kafka 점유를 억제한다.
        }
    }

    public boolean dispatchOnce() {
        Optional<ClaimedOutboxEvent> claimed = claimService.claimNext();
        if (claimed.isEmpty()) {
            return false;
        }
        ClaimedOutboxEvent event = claimed.get();
        try {
            kafkaTemplate.send(event.topic(), event.recordKey(), event.payloadJson())
                    .get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!claimService.markSent(event.eventId(), event.claimToken())) {
                log.warn("outbox ACK 후 소유권 변경 감지: eventId={}", event.eventId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 종료/중단된 스케줄러에서 DB를 다시 만지지 않는다. lease 만료 후 재선점된다.
            log.warn("outbox 발행 대기 중단: eventId={}; lease 만료 후 재시도", event.eventId(), e);
            return false;
        } catch (Exception e) {
            retry(event, e);
        }
        return true;
    }

    private void retry(ClaimedOutboxEvent event, Exception error) {
        log.warn("outbox 발행 실패: eventId={}, topic={}, attempts={}",
                event.eventId(), event.topic(), event.attempts(), error);
        if (!claimService.releaseForRetry(event.eventId(), event.claimToken(),
                event.attempts(), error.toString())) {
            log.warn("outbox 실패 처리 중 소유권 변경 감지: eventId={}", event.eventId());
        }
    }
}
