package com.buildflow.site.domain.site.dto;

import com.buildflow.site.domain.client.dto.ClientResponse;
import com.buildflow.site.domain.site.entity.Site;
import com.buildflow.site.domain.site.entity.SiteStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
public class SiteResponse {

    private Long id;
    private String siteName;
    private ClientResponse client;
    private String address;
    private SiteStatus status;
    private LocalDate startDate;
    private LocalDate endDate;
    private String memo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static SiteResponse from(Site site) {
        return SiteResponse.builder()
                .id(site.getId())
                .siteName(site.getSiteName())
                .client(site.getClient() != null ? ClientResponse.from(site.getClient()) : null)
                .address(site.getAddress())
                .status(site.getStatus())
                .startDate(site.getStartDate())
                .endDate(site.getEndDate())
                .memo(site.getMemo())
                .createdAt(site.getCreatedAt())
                .updatedAt(site.getUpdatedAt())
                .build();
    }
}
