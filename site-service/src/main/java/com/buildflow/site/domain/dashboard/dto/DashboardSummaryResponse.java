package com.buildflow.site.domain.dashboard.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class DashboardSummaryResponse {

    private String summary;
    private LocalDateTime generatedAt;
}
