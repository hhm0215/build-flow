package com.buildflow.notification.domain.warranty.service;

import com.buildflow.notification.domain.warranty.dto.WarrantyCreateRequest;
import com.buildflow.notification.domain.warranty.dto.WarrantyResponse;
import com.buildflow.notification.domain.warranty.dto.WarrantyUpdateRequest;
import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import com.buildflow.notification.global.exception.BusinessException;
import com.buildflow.notification.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefectWarrantyService {

    private final DefectWarrantyRepository warrantyRepository;

    @Transactional
    public WarrantyResponse create(WarrantyCreateRequest request) {
        DefectWarranty warranty = DefectWarranty.builder()
                .siteId(request.getSiteId())
                .insuranceCompany(request.getInsuranceCompany())
                .policyNumber(request.getPolicyNumber())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .memo(request.getMemo())
                .build();

        return WarrantyResponse.from(warrantyRepository.save(warranty));
    }

    public List<WarrantyResponse> findAll(Long siteId) {
        List<DefectWarranty> warranties = (siteId != null)
                ? warrantyRepository.findBySiteIdOrderByEndDateAsc(siteId)
                : warrantyRepository.findAllByOrderByEndDateAsc();

        return warranties.stream()
                .map(WarrantyResponse::from)
                .toList();
    }

    public WarrantyResponse findById(Long id) {
        return WarrantyResponse.from(getWarranty(id));
    }

    @Transactional
    public WarrantyResponse update(Long id, WarrantyUpdateRequest request) {
        DefectWarranty warranty = getWarranty(id);
        warranty.update(
                request.getInsuranceCompany(),
                request.getPolicyNumber(),
                request.getStartDate(),
                request.getEndDate(),
                request.getMemo()
        );
        return WarrantyResponse.from(warranty);
    }

    @Transactional
    public void delete(Long id) {
        DefectWarranty warranty = getWarranty(id);
        warrantyRepository.delete(warranty);
    }

    public List<WarrantyResponse> findExpiringSoon(int days) {
        LocalDate now = LocalDate.now();
        LocalDate threshold = now.plusDays(days);
        return warrantyRepository.findExpiringSoon(now, threshold).stream()
                .map(WarrantyResponse::from)
                .toList();
    }

    private DefectWarranty getWarranty(Long id) {
        return warrantyRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.WARRANTY_NOT_FOUND));
    }
}
