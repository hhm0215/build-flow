package com.buildflow.notification.domain.warranty.service;

import com.buildflow.notification.domain.notification.service.NotificationService;
import com.buildflow.notification.domain.warranty.WarrantyDatePolicy;
import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.entity.OcrStatus;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class WarrantyExpiryNotificationService {

    private final DefectWarrantyRepository warrantyRepository;
    private final NotificationService notificationService;

    @Value("${app.warranty.alert-threshold-days:30}")
    private int thresholdDays;

    /** Recheck a possibly old Kafka event and insert the receipt with the notification atomically. */
    @Transactional
    public void consume(String eventId, Long warrantyId, Long siteId,
                        String insuranceCompany, LocalDate endDate, String expiryConfirmationRef) {
        DefectWarranty warranty = warrantyRepository.findByIdForUpdate(warrantyId).orElse(null);
        LocalDate today = WarrantyDatePolicy.today();
        if (warranty == null
                || warranty.getOcrStatus() != OcrStatus.MANUAL
                || warranty.getExpiryConfirmedAt() == null
                || !Objects.equals(expiryConfirmationRef, warranty.getExpiryConfirmedAt().toString())
                || warranty.getInsuranceCompany() == null || warranty.getInsuranceCompany().isBlank()
                || warranty.getStartDate() == null || warranty.getEndDate() == null
                || warranty.getStartDate().isAfter(warranty.getEndDate())
                || warranty.getEndDate().isBefore(today)
                || warranty.getEndDate().isAfter(today.plusDays(thresholdDays))
                || !Objects.equals(siteId, warranty.getSiteId())
                || !Objects.equals(insuranceCompany, warranty.getInsuranceCompany())
                || !Objects.equals(endDate, warranty.getEndDate())) {
            // A receipt prevents a later replay from reviving an invalid, stale event.
            notificationService.recordIgnoredEvent(eventId);
            return;
        }

        long daysUntilExpiry = ChronoUnit.DAYS.between(today, warranty.getEndDate());
        notificationService.createNotification(eventId, "WARRANTY_EXPIRING",
                String.format("하자보증보험(ID: %d, %s) 만료까지 %d일 남았습니다.",
                        warrantyId, warranty.getInsuranceCompany(), daysUntilExpiry),
                warranty.getSiteId());
    }
}
