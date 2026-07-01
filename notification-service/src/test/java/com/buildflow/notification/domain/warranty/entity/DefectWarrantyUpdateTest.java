package com.buildflow.notification.domain.warranty.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DefectWarranty#update(String, LocalDate, LocalDate, Optional, Optional, Optional)}의
 * 3-state 시맨틱(skip / clear / update)이 필드별로 정확히 동작함을 lock-in.
 */
class DefectWarrantyUpdateTest {

    private DefectWarranty seed() {
        return DefectWarranty.builder()
                .siteId(1L)
                .insuranceCompany("삼성화재")
                .policyNumber("SS-2026-01")
                .startDate(LocalDate.of(2026, 1, 1))
                .endDate(LocalDate.of(2027, 1, 1))
                .coverageAmount(10_000_000L)
                .memo("초기 메모")
                .build();
    }

    @Test
    void null_Optional_인자는_기존_값_유지() {
        DefectWarranty w = seed();
        w.update("한화손해보험",
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2027, 3, 1),
                null, null, null);
        assertThat(w.getInsuranceCompany()).isEqualTo("한화손해보험");
        assertThat(w.getPolicyNumber()).isEqualTo("SS-2026-01");
        assertThat(w.getCoverageAmount()).isEqualTo(10_000_000L);
        assertThat(w.getMemo()).isEqualTo("초기 메모");
    }

    @Test
    void Optional_empty_는_null_로_clear() {
        DefectWarranty w = seed();
        w.update("삼성화재",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2027, 1, 1),
                Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(w.getPolicyNumber()).isNull();
        assertThat(w.getCoverageAmount()).isNull();
        assertThat(w.getMemo()).isNull();
    }

    @Test
    void Optional_of_는_새_값으로_갱신() {
        DefectWarranty w = seed();
        w.update("삼성화재",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2027, 1, 1),
                Optional.of("SS-2026-99"),
                Optional.of(50_000_000L),
                Optional.of("업데이트 메모"));
        assertThat(w.getPolicyNumber()).isEqualTo("SS-2026-99");
        assertThat(w.getCoverageAmount()).isEqualTo(50_000_000L);
        assertThat(w.getMemo()).isEqualTo("업데이트 메모");
    }

    @Test
    void 필수_필드는_항상_새_값으로_대입() {
        DefectWarranty w = seed();
        w.update("현대해상",
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2027, 6, 1),
                null, null, null);
        assertThat(w.getInsuranceCompany()).isEqualTo("현대해상");
        assertThat(w.getStartDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(w.getEndDate()).isEqualTo(LocalDate.of(2027, 6, 1));
    }
}
