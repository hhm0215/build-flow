package com.buildflow.notification.domain.warranty.service;

import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.entity.OcrStatus;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/** OCR 연산 밖의 짧은 트랜잭션에서 PENDING 행만 잠그고 결과를 반영한다. */
@Service
@RequiredArgsConstructor
public class WarrantyOcrResultService {

    private final DefectWarrantyRepository repository;

    @Transactional
    public boolean apply(Long id, String insurer, String policyNumber, LocalDate startDate, LocalDate endDate) {
        DefectWarranty warranty = repository.findByIdForUpdate(id).orElse(null);
        if (warranty == null || warranty.getOcrStatus() != OcrStatus.PENDING) return false;
        warranty.applyOcrResult(insurer, policyNumber, startDate, endDate);
        return true;
    }

    @Transactional
    public void markFailed(Long id) {
        repository.findByIdForUpdate(id).ifPresent(warranty -> {
            if (warranty.getOcrStatus() == OcrStatus.PENDING) warranty.markOcrFailed();
        });
    }
}
