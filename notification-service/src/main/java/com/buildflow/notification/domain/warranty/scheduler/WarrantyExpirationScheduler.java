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
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class WarrantyExpirationScheduler {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final DefectWarrantyRepository warrantyRepository;
    private final KafkaProducerService kafkaProducerService;

    @Value("${app.warranty.alert-threshold-days:30}")
    private int thresholdDays;

    @Value("${app.warranty.alert-cooldown-days:7}")
    private int cooldownDays;

    /**
     * 매일 09:00 KST — 만료 임박 보증보험을 찾아 warranty.expiring 토픽 발행.
     *
     * <p>발송 후 cooldownDays 이내에는 같은 보증서 재발송 안 함.
     * Kafka 발행 실패(broker 일시 장애) 시 markExpiringAlertSent 호출하지 않음 →
     * 다음 cron(24h)에서 자동 재시도.
     *
     * <p>Timezone: 서버 컨테이너가 UTC여도 cron / LocalDate.now 모두 Asia/Seoul 기준으로 작동.
     */
    @Scheduled(cron = "${app.warranty.scheduler-cron:0 0 9 * * *}", zone = "Asia/Seoul")
    @Transactional
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

        log.info("만료 임박 보증보험 {}건 알림 발송 시작 (threshold={}일)", targets.size(), thresholdDays);

        int sent = 0;
        int failed = 0;
        for (DefectWarranty warranty : targets) {
            long daysUntilExpiry = ChronoUnit.DAYS.between(today, warranty.getEndDate());
            WarrantyExpiringPayload payload = WarrantyExpiringPayload.of(warranty, daysUntilExpiry);
            try {
                // 동기 대기 — broker 실패 시 다음 cron에서 재시도 (markExpiringAlertSent 호출 안 함)
                kafkaProducerService.sendWarrantyExpiring(payload).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                warranty.markExpiringAlertSent(today);
                sent++;
            } catch (Exception e) {
                log.error("Kafka 발행 실패 — 다음 cron에서 재시도: warrantyId={} cause={}",
                        warranty.getId(), e.getMessage());
                failed++;
            }
        }
        log.info("만료 임박 알림 발송 완료: 성공={} 실패={}", sent, failed);
    }
}
