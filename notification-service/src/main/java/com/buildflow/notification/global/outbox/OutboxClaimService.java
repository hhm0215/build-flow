package com.buildflow.notification.global.outbox;

import com.buildflow.notification.domain.warranty.WarrantyDatePolicy;
import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxClaimService {

    private final OutboxEventRepository repository;
    private final DefectWarrantyRepository warrantyRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.warranty.alert-threshold-days:30}")
    private int thresholdDays;

    @Transactional
    public OutboxClaimAttempt claimNext() {
        Instant now = Instant.now();
        if (thresholdDays < 0) throw new IllegalStateException("Invalid warranty alert threshold");
        OutboxEvent event = repository.claimReady(now).orElse(null);
        if (event == null) return OutboxClaimAttempt.empty();
        if ("warranty.expiring".equals(event.getTopic())) {
            String reason = WarrantyOutboxEligibility.rejectionReason(event,
                    warrantyFor(event), WarrantyDatePolicy.today(), thresholdDays, objectMapper);
            if (reason != null) {
                event.suppress(now, reason);
                return OutboxClaimAttempt.suppressed();
            }
        }
        String token = UUID.randomUUID().toString();
        event.claim(token, now.plusSeconds(60));
        return OutboxClaimAttempt.claimed(new OutboxClaim(event.getEventId(), event.getTopic(),
                event.getRecordKey(), event.getPayloadJson(), token));
    }

    private DefectWarranty warrantyFor(OutboxEvent event) {
        try {
            long warrantyId = Long.parseLong(event.getRecordKey());
            if (warrantyId <= 0 || !Long.toString(warrantyId).equals(event.getRecordKey())) return null;
            return warrantyRepository.findByIdForUpdate(warrantyId).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
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
