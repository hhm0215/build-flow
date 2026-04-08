package com.buildflow.estimate.domain.estimate.service;

import com.buildflow.estimate.domain.estimate.dto.*;
import com.buildflow.estimate.domain.estimate.entity.Estimate;
import com.buildflow.estimate.domain.estimate.entity.EstimateItem;
import com.buildflow.estimate.domain.estimate.entity.EstimateStatus;
import com.buildflow.estimate.domain.estimate.repository.EstimateRepository;
import com.buildflow.estimate.global.exception.BusinessException;
import com.buildflow.estimate.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EstimateService {

    private final EstimateRepository estimateRepository;

    @Transactional
    public EstimateResponse create(EstimateCreateRequest request) {
        BigDecimal totalAmount = calculateTotal(request.getItems());

        Estimate estimate = Estimate.builder()
                .siteId(request.getSiteId())
                .title(request.getTitle())
                .estimateDate(request.getEstimateDate())
                .totalAmount(totalAmount)
                .memo(request.getMemo())
                .build();

        addItems(estimate, request.getItems());
        estimateRepository.save(estimate);

        return EstimateResponse.from(estimate);
    }

    public List<EstimateResponse> findBySiteId(Long siteId) {
        return estimateRepository.findBySiteIdOrderByCreatedAtDesc(siteId)
                .stream()
                .map(EstimateResponse::from)
                .toList();
    }

    public List<EstimateResponse> findAll() {
        return estimateRepository.findAll()
                .stream()
                .map(EstimateResponse::from)
                .toList();
    }

    public EstimateResponse findById(Long id) {
        Estimate estimate = getEstimate(id);
        return EstimateResponse.from(estimate);
    }

    @Transactional
    public EstimateResponse update(Long id, EstimateUpdateRequest request) {
        Estimate estimate = getEstimate(id);

        if (estimate.getStatus() == EstimateStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.ESTIMATE_ALREADY_CONFIRMED);
        }

        BigDecimal totalAmount = calculateTotal(request.getItems());

        estimate.update(request.getTitle(), request.getEstimateDate(), totalAmount, request.getMemo());
        estimate.clearItems();
        addItems(estimate, request.getItems());

        return EstimateResponse.from(estimate);
    }

    @Transactional
    public void delete(Long id) {
        Estimate estimate = getEstimate(id);
        estimateRepository.delete(estimate);
    }

    @Transactional
    public EstimateResponse confirm(Long id) {
        Estimate estimate = getEstimate(id);

        if (estimate.getStatus() == EstimateStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.ESTIMATE_ALREADY_CONFIRMED);
        }

        estimate.confirm();
        return EstimateResponse.from(estimate);
    }

    private Estimate getEstimate(Long id) {
        return estimateRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.ESTIMATE_NOT_FOUND));
    }

    private BigDecimal calculateTotal(List<EstimateItemRequest> items) {
        return items.stream()
                .map(item -> item.getUnitPrice().multiply(item.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void addItems(Estimate estimate, List<EstimateItemRequest> itemRequests) {
        itemRequests.forEach(req -> {
            BigDecimal amount = req.getUnitPrice().multiply(req.getQuantity());
            EstimateItem item = EstimateItem.builder()
                    .estimate(estimate)
                    .itemName(req.getItemName())
                    .unit(req.getUnit())
                    .quantity(req.getQuantity())
                    .unitPrice(req.getUnitPrice())
                    .amount(amount)
                    .build();
            estimate.addItem(item);
        });
    }
}
