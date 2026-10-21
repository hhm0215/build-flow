package com.buildflow.site.domain.site.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
public class SiteUpdateRequest {

    @NotBlank(message = "현장명은 필수입니다.")
    @Size(max = 200, message = "현장명은 200자 이하여야 합니다.")
    private String siteName;

    private Long clientId;
    @Size(max = 500, message = "주소는 500자 이하여야 합니다.")
    private String address;
    private LocalDate startDate;
    private LocalDate endDate;
    // Java UTF-16 16,000 code units는 utf8mb4 최악에도 64,000 bytes로 MySQL TEXT 한도 이내다.
    @Size(max = 16000, message = "메모는 16000자 이하여야 합니다.")
    private String memo;

    @AssertTrue(message = "종료일은 시작일보다 빠를 수 없습니다.")
    public boolean isDateRangeValid() {
        return startDate == null || endDate == null || !endDate.isBefore(startDate);
    }
}
