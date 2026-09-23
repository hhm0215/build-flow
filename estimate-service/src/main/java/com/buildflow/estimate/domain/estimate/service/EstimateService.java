package com.buildflow.estimate.domain.estimate.service;

import com.buildflow.estimate.domain.estimate.dto.*;
import com.buildflow.estimate.domain.estimate.entity.Estimate;
import com.buildflow.estimate.domain.estimate.entity.EstimateItem;
import com.buildflow.estimate.domain.estimate.entity.EstimateStatus;
import com.buildflow.estimate.domain.estimate.event.EstimateParsedPayload;
import com.buildflow.estimate.domain.estimate.repository.EstimateRepository;
import com.buildflow.estimate.global.exception.BusinessException;
import com.buildflow.estimate.global.exception.ErrorCode;
import com.buildflow.estimate.global.kafka.KafkaProducerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EstimateService {

    private static final BigDecimal MAX_QUANTITY = new BigDecimal("99999999.99");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999.99");

    private final EstimateRepository estimateRepository;
    private final KafkaProducerService kafkaProducerService;

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
        Estimate estimate = getEstimateForUpdate(id);

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
        Estimate estimate = getEstimateForUpdate(id);

        if (estimate.getStatus() == EstimateStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.CONFIRMED_ESTIMATE_DELETE_NOT_ALLOWED);
        }

        estimateRepository.delete(estimate);
    }

    @Transactional
    public EstimateResponse confirm(Long id) {
        Estimate estimate = getEstimateForUpdate(id);

        if (estimate.getStatus() == EstimateStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.ESTIMATE_ALREADY_CONFIRMED);
        }

        estimate.confirm();

        kafkaProducerService.sendEstimateParsed(EstimateParsedPayload.builder()
                .estimateId(estimate.getId())
                .siteId(estimate.getSiteId())
                .totalAmount(estimate.getTotalAmount())
                .build());

        return EstimateResponse.from(estimate);
    }

    private Estimate getEstimate(Long id) {
        return estimateRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.ESTIMATE_NOT_FOUND));
    }

    private Estimate getEstimateForUpdate(Long id) {
        return estimateRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.ESTIMATE_NOT_FOUND));
    }

    private BigDecimal calculateTotal(List<EstimateItemRequest> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (EstimateItemRequest item : items) {
            BigDecimal quantity = item.getQuantity();
            BigDecimal unitPrice = item.getUnitPrice();
            if (quantity == null || unitPrice == null || quantity.signum() <= 0
                    || unitPrice.signum() < 0 || quantity.compareTo(MAX_QUANTITY) > 0
                    || unitPrice.compareTo(MAX_AMOUNT) > 0) {
                throw new BusinessException(ErrorCode.INVALID_ESTIMATE_AMOUNT);
            }
            try {
                quantity.setScale(2, RoundingMode.UNNECESSARY);
                unitPrice.setScale(2, RoundingMode.UNNECESSARY);
                BigDecimal amount = quantity.multiply(unitPrice).setScale(2, RoundingMode.UNNECESSARY);
                total = total.add(amount);
                if (total.compareTo(MAX_AMOUNT) > 0) {
                    throw new BusinessException(ErrorCode.INVALID_ESTIMATE_AMOUNT);
                }
            } catch (ArithmeticException ignored) {
                throw new BusinessException(ErrorCode.INVALID_ESTIMATE_AMOUNT);
            }
        }
        return total;
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
