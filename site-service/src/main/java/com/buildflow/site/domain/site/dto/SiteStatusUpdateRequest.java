package com.buildflow.site.domain.site.dto;

import com.buildflow.site.domain.site.entity.SiteStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SiteStatusUpdateRequest {

    @NotNull(message = "변경할 상태값은 필수입니다.")
    private SiteStatus status;
}
