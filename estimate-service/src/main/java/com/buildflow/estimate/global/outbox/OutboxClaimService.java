package com.buildflow.estimate.global.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxClaimService {

    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final long MAX_BACKOFF_SECONDS = 300;

    private final OutboxEventRepository repository;

    @Transactional
    public Optional<OutboxClaim> claimNext() {
        Instant now = Instant.now();
        return repository.lockNextAvailable(now).map(event -> {
            String token = UUID.randomUUID().toString();
            event.claim(token, now.plus(LEASE));
            return new OutboxClaim(event.getEventId(), event.getTopic(), event.getRecordKey(),
                    event.getPayloadJson(), token, event.getAttempts());
        });
    }

    @Transactional
    public boolean markSent(OutboxClaim claim) {
        int updated = repository.markSentIfClaimed(claim.eventId(), claim.token(), Instant.now());
        if (updated == 0) {
            log.warn("만료되거나 회수된 outbox claim의 ACK 무시: eventId={}", claim.eventId());
        }
        return updated == 1;
    }

    @Transactional
    public boolean markFailed(OutboxClaim claim, Exception error) {
        // attempts counts completed failures and is incremented by the conditional update.
        int failures = claim.attempts() + 1;
        Instant nextAttemptAt = Instant.now().plus(backoff(failures));
        String summary = error.getClass().getSimpleName() + ": " + error.getMessage();
        if (summary.length() > 1000) {
            summary = summary.substring(0, 1000);
        }
        int updated = repository.markFailedIfClaimed(
                claim.eventId(), claim.token(), nextAttemptAt, summary);
        if (updated == 0) {
            log.warn("만료되거나 회수된 outbox claim의 실패 갱신 무시: eventId={}", claim.eventId());
        }
        return updated == 1;
    }

    static Duration backoff(int failures) {
        long seconds = 1L << Math.min(Math.max(failures - 1, 0), 9);
        return Duration.ofSeconds(Math.min(seconds, MAX_BACKOFF_SECONDS));
    }
}
