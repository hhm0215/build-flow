package com.buildflow.notification.domain.warranty.scheduler;

import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.event.WarrantyExpiringPayload;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import com.buildflow.notification.global.kafka.KafkaProducerService;
import com.buildflow.notification.global.outbox.OutboxEventRepository;
import com.buildflow.notification.global.outbox.OutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WarrantyAlertService {

    private final DefectWarrantyRepository warrantyRepository;
    private final KafkaProducerService kafkaProducerService;
    private final OutboxEventRepository outboxEventRepository;

    /** The cooldown marker and durable outbox event must commit together. */
    @Transactional
    public boolean enqueueIfEligible(Long warrantyId, LocalDate today, LocalDate threshold,
                                     LocalDate cooldownThreshold) {
        DefectWarranty warranty = warrantyRepository.findByIdForUpdate(warrantyId).orElse(null);
        if (warranty == null || warranty.getEndDate() == null
                || warranty.getEndDate().isBefore(today) || warranty.getEndDate().isAfter(threshold)
                || (warranty.getLastExpiringAlertSentAt() != null
                && warranty.getLastExpiringAlertSentAt().isAfter(cooldownThreshold))) {
            return false;
        }

        // A broker outage can exceed the cooldown. The warranty row lock serializes
        // this check with enqueue, so a still-pending alert cannot gain a new eventId.
        if (outboxEventRepository.existsByTopicAndRecordKeyAndStatusIn(
                "warranty.expiring", warrantyId.toString(),
                List.of(OutboxStatus.PENDING, OutboxStatus.CLAIMED))) {
            return false;
        }

        long daysUntilExpiry = ChronoUnit.DAYS.between(today, warranty.getEndDate());
        kafkaProducerService.sendWarrantyExpiring(WarrantyExpiringPayload.of(warranty, daysUntilExpiry));
        warranty.markExpiringAlertSent(today);
        return true;
    }
}
