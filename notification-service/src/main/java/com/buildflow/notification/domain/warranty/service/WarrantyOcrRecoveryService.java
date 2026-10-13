package com.buildflow.notification.domain.warranty.service;

import com.buildflow.notification.domain.warranty.entity.OcrStatus;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** 재시작/비정상 종료로 사라진 메모리 큐 작업을 수동 보정 가능한 상태로 전환한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WarrantyOcrRecoveryService {

    private final DefectWarrantyRepository repository;
    private final WarrantyOcrResultService resultService;

    @Scheduled(initialDelayString = "${app.ocr.recovery-initial-delay-ms:60000}",
            fixedDelayString = "${app.ocr.recovery-interval-ms:600000}")
    public void failStalePending() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);
        var stale = repository.findTop100ByOcrStatusAndCreatedAtBeforeOrderByCreatedAtAsc(OcrStatus.PENDING, cutoff);
        for (var warranty : stale) {
            resultService.markFailed(warranty.getId());
        }
        if (!stale.isEmpty()) {
            log.warn("시간 초과 OCR PENDING 정리 count={}", stale.size());
        }
    }
}
