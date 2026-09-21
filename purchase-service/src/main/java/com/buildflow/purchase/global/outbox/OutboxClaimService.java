package com.buildflow.purchase.global.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OutboxClaimService {

    private static final long LEASE_SECONDS = 60;
    private static final long MAX_BACKOFF_SECONDS = 300;

    private final OutboxEventRepository repository;

    @Transactional
    public Optional<ClaimedOutboxEvent> claimNext() {
        LocalDateTime now = LocalDateTime.now();
        return repository.findNextClaimableForUpdate(now).map(event -> {
            String token = event.claim(now, now.plusSeconds(LEASE_SECONDS));
            repository.saveAndFlush(event);
            return new ClaimedOutboxEvent(event.getEventId(), event.getTopic(), event.getRecordKey(),
                    event.getPayloadJson(), token, event.getAttempts());
        });
    }

    @Transactional
    public boolean markSent(String eventId, String token) {
        Optional<OutboxEvent> locked = repository.findByIdForUpdate(eventId);
        if (locked.isEmpty() || !locked.get().isClaimedBy(token)) {
            return false;
        }
        locked.get().markSent(LocalDateTime.now());
        return true;
    }

    @Transactional
    public boolean releaseForRetry(String eventId, String token, int attempts, String error) {
        Optional<OutboxEvent> locked = repository.findByIdForUpdate(eventId);
        if (locked.isEmpty() || !locked.get().isClaimedBy(token)) {
            return false;
        }
        long exponential = 1L << Math.min(Math.max(attempts - 1, 0), 9);
        long delaySeconds = Math.min(MAX_BACKOFF_SECONDS, exponential);
        locked.get().releaseForRetry(LocalDateTime.now().plusSeconds(delaySeconds), error);
        return true;
    }
}
