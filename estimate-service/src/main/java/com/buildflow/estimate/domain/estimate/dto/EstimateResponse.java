package com.buildflow.estimate.domain.estimate.dto;

import com.buildflow.estimate.domain.estimate.entity.Estimate;
import com.buildflow.estimate.domain.estimate.entity.EstimateStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class EstimateResponse {

    private Long id;
    private Long siteId;
    private String title;
    private EstimateStatus status;
    private LocalDate estimateDate;
    private BigDecimal totalAmount;
    private String memo;
    private List<EstimateItemResponse> items;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static EstimateResponse from(Estimate estimate) {
        return EstimateResponse.builder()
                .id(estimate.getId())
                .siteId(estimate.getSiteId())
                .title(estimate.getTitle())
                .status(estimate.getStatus())
                .estimateDate(estimate.getEstimateDate())
                .totalAmount(estimate.getTotalAmount())
                .memo(estimate.getMemo())
                .items(estimate.getItems().stream()
                        .map(EstimateItemResponse::from)
                        .toList())
                .createdAt(estimate.getCreatedAt())
                .updatedAt(estimate.getUpdatedAt())
                .build();
    }
}
