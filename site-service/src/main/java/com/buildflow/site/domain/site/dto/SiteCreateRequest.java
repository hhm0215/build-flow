package com.buildflow.site.domain.site.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
public class SiteCreateRequest {

    @NotBlank(message = "현장명은 필수입니다.")
    private String siteName;

    private Long clientId;
    private String address;
    private LocalDate startDate;
    private LocalDate endDate;
    private String memo;
}
