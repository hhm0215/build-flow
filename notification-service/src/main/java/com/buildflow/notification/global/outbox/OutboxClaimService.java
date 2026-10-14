package com.buildflow.notification.global.outbox;

import com.buildflow.notification.domain.warranty.WarrantyDatePolicy;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxClaimService {

    private final OutboxEventRepository repository;
    private final DefectWarrantyRepository warrantyRepository;

    @Transactional
    public Optional<OutboxClaim> claimNext() {
        Instant now = Instant.now();
        return repository.claimReady(now).map(event -> {
            String token = UUID.randomUUID().toString();
            event.claim(token, now.plusSeconds(60));
            return new OutboxClaim(event.getEventId(), event.getTopic(),
                    event.getRecordKey(), event.getPayloadJson(), token);
        });
    }

    @Transactional
    public boolean markSent(OutboxClaim claim) {
        return repository.findByIdForUpdate(claim.eventId())
                .map(event -> {
                    if (!event.markSent(claim.claimToken(), Instant.now())) return false;
                    // The enqueue date suppresses repeats while pending. On broker ACK,
                    // restart the cooldown from the actual delivery date.
                    if ("warranty.expiring".equals(event.getTopic())) {
                        Long warrantyId = Long.valueOf(event.getRecordKey());
                        warrantyRepository.findByIdForUpdate(warrantyId)
                                .filter(warranty -> warranty.getExpiryConfirmedAt() != null
                                        && !event.getCreatedAt().isBefore(warranty.getExpiryConfirmedAt()))
                                .ifPresent(warranty -> warranty.markExpiringAlertSent(WarrantyDatePolicy.today()));
                    }
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public boolean markFailed(OutboxClaim claim, String error) {
        return repository.findByIdForUpdate(claim.eventId())
                .map(event -> event.markFailed(claim.claimToken(), Instant.now(), error))
                .orElse(false);
    }
}
