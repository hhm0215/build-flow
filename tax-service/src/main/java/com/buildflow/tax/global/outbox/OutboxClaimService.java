package com.buildflow.tax.global.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxClaimService {

    private static final long LEASE_SECONDS = 60;
    private static final long MAX_BACKOFF_MILLIS = 300_000;

    private final OutboxEventRepository repository;

    @Transactional
    public Optional<OutboxClaim> claimNext(LocalDateTime now) {
        return repository.findNextClaimable(now).map(event -> {
            String token = UUID.randomUUID().toString();
            event.claim(token, now.plusSeconds(LEASE_SECONDS));
            repository.flush();
            return new OutboxClaim(event.getEventId(), event.getTopic(), event.getRecordKey(),
                    event.getPayloadJson(), token, event.getAttempts());
        });
    }

    @Transactional
    public boolean markSent(OutboxClaim claim, LocalDateTime now) {
        return repository.markSent(claim.eventId(), claim.claimToken(), now) == 1;
    }

    @Transactional
    public boolean markFailed(OutboxClaim claim, LocalDateTime now, Exception failure) {
        long backoff = Math.min(MAX_BACKOFF_MILLIS,
                1000L << Math.min(Math.max(claim.attempts() - 1, 0), 9));
        String error = failure.toString();
        if (error.length() > 1000) {
            error = error.substring(0, 1000);
        }
        return repository.markFailed(claim.eventId(), claim.claimToken(),
                now.plusNanos(backoff * 1_000_000), error) == 1;
    }
}
