package com.buildflow.notification.domain.warranty.service;

import com.buildflow.notification.domain.warranty.WarrantyDatePolicy;
import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.entity.WarrantyFileRetention;
import com.buildflow.notification.domain.warranty.repository.WarrantyFileRetentionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/** 보증 삭제 뒤 원본 PDF를 보존 기간 동안 두고, 기한이 지난 것만 폐기한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WarrantyFileRetentionService {

    static final Pattern STORED_NAME = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}[.]pdf");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final WarrantyFileRetentionRepository repository;

    @Value("${app.upload-dir:./uploads/warranties}")
    private String uploadDir;

    @Value("${app.warranty.deleted-file-retention-days:365}")
    private int retentionDays;

    /** 보증 삭제와 같은 트랜잭션에서 호출한다. 파일은 지우지 않는다. */
    @Transactional
    public void retain(DefectWarranty warranty) {
        String filePath = warranty.getFilePath();
        if (filePath == null) {
            return;
        }
        String storedName = Paths.get(filePath).getFileName().toString();
        if (!STORED_NAME.matcher(storedName).matches()) {
            // 앱이 만든 이름이 아니면 정리 대상에 올리지 않는다(지우지도 않는다)
            log.warn("보존 대상 파일명이 형식과 달라 정리 목록에서 제외 warrantyId={}", warranty.getId());
            return;
        }
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.SECONDS);
        repository.save(WarrantyFileRetention.retain(warranty.getId(), storedName, now,
                WarrantyDatePolicy.today().plusDays(retentionDays)));
    }

    @Scheduled(cron = "${app.warranty.retention-purge-cron:0 30 3 * * *}", zone = "Asia/Seoul")
    @Transactional
    public int purgeExpired() {
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        int purged = 0;
        for (WarrantyFileRetention retention :
                repository.findTop100ByPurgedAtIsNullAndPurgeAfterLessThanEqualOrderByIdAsc(WarrantyDatePolicy.today())) {
            String name = retention.getStoredName();
            Path target = root.resolve(name).normalize();
            if (!STORED_NAME.matcher(name).matches() || !root.equals(target.getParent())) {
                log.warn("보존 파일 정리 거부(이름·경로 검사 실패) retentionId={}", retention.getId());
                continue;
            }
            try {
                Files.deleteIfExists(target);
                retention.markPurged(LocalDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.SECONDS));
                purged++;
            } catch (IOException e) {
                log.warn("보존 파일 정리 실패 retentionId={} causeType={}", retention.getId(), e.getClass().getSimpleName());
            }
        }
        if (purged > 0) {
            log.info("보존 기간이 지난 보증 원본 정리 count={}", purged);
        }
        return purged;
    }
}
