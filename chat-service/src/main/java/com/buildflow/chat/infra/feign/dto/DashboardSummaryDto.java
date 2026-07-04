package com.buildflow.chat.infra.feign.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class DashboardSummaryDto {
    private String summary;
    private String generatedAt;
}
