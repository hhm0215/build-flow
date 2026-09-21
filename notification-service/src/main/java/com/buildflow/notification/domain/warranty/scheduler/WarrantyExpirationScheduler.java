package com.buildflow.notification.domain.warranty.scheduler;

import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class WarrantyExpirationScheduler {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final DefectWarrantyRepository warrantyRepository;
    private final WarrantyAlertService warrantyAlertService;

    @Value("${app.warranty.alert-threshold-days:30}")
    private int thresholdDays;

    @Value("${app.warranty.alert-cooldown-days:7}")
    private int cooldownDays;

    /**
     * 매일 09:00 KST — 만료 임박 보증보험을 찾아 outbox에 기록한다.
     * 후보 목록은 힌트이며, 개별 보증 행을 잠근 후 자격과 cooldown을 다시 확인한다.
     */
    @Scheduled(cron = "${app.warranty.scheduler-cron:0 0 9 * * *}", zone = "Asia/Seoul")
    public void notifyExpiringWarranties() {
        LocalDate today = LocalDate.now(KST);
        LocalDate threshold = today.plusDays(thresholdDays);
        LocalDate cooldownThreshold = today.minusDays(cooldownDays);

        List<DefectWarranty> targets = warrantyRepository.findExpiringNotYetAlerted(
                today, threshold, cooldownThreshold);

        if (targets.isEmpty()) {
            log.debug("만료 임박 보증보험 없음 (threshold={}일, cooldown={}일)", thresholdDays, cooldownDays);
            return;
        }

        log.info("만료 임박 보증보험 {}건 outbox 확인 시작 (threshold={}일)", targets.size(), thresholdDays);

        int queued = 0;
        int failed = 0;
        for (DefectWarranty warranty : targets) {
            try {
                if (warrantyAlertService.enqueueIfEligible(
                        warranty.getId(), today, threshold, cooldownThreshold)) {
                    queued++;
                }
            } catch (Exception e) {
                log.error("하자보증보험 만료 알림 enqueue 실패: warrantyId={}", warranty.getId(), e);
                failed++;
            }
        }
        log.info("하자보증보험 만료 알림 outbox 처리: 대기={} 실패={}", queued, failed);
    }
}
