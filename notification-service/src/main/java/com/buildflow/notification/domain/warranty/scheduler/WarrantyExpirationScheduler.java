package com.buildflow.notification.domain.warranty.scheduler;

import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.event.WarrantyExpiringPayload;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import com.buildflow.notification.global.kafka.KafkaProducerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class WarrantyExpirationScheduler {

    private final DefectWarrantyRepository warrantyRepository;
    private final KafkaProducerService kafkaProducerService;

    @Value("${app.warranty.alert-threshold-days:30}")
    private int thresholdDays;

    @Value("${app.warranty.alert-cooldown-days:7}")
    private int cooldownDays;

    /**
     * 매일 09:00 — 만료 임박 보증보험을 찾아 warranty.expiring 토픽 발행.
     * 발송 후 cooldownDays 이내에는 같은 보증서 재발송 안 함.
     */
    @Scheduled(cron = "${app.warranty.scheduler-cron:0 0 9 * * *}")
    @Transactional
    public void notifyExpiringWarranties() {
        LocalDate today = LocalDate.now();
        LocalDate threshold = today.plusDays(thresholdDays);
        LocalDate cooldownThreshold = today.minusDays(cooldownDays);

        List<DefectWarranty> targets = warrantyRepository.findExpiringNotYetAlerted(
                today, threshold, cooldownThreshold);

        if (targets.isEmpty()) {
            log.debug("만료 임박 보증보험 없음 (threshold={}일, cooldown={}일)", thresholdDays, cooldownDays);
            return;
        }

        log.info("만료 임박 보증보험 {}건 알림 발송 시작 (threshold={}일)", targets.size(), thresholdDays);

        for (DefectWarranty warranty : targets) {
            long daysUntilExpiry = ChronoUnit.DAYS.between(today, warranty.getEndDate());
            kafkaProducerService.sendWarrantyExpiring(
                    WarrantyExpiringPayload.of(warranty, daysUntilExpiry));
            warranty.markExpiringAlertSent(today);
        }
    }
}
