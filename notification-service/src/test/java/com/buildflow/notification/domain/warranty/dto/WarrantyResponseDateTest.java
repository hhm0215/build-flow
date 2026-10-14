package com.buildflow.notification.domain.warranty.dto;

import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class WarrantyResponseDateTest {

    @Test
    void 만료_당일에는_미만료이고_다음날에는_만료로_표시한다() {
        DefectWarranty warranty = DefectWarranty.builder()
                .siteId(1L)
                .insuranceCompany("[QA] 보험사")
                .startDate(LocalDate.of(2026, 10, 1))
                .endDate(LocalDate.of(2026, 10, 13))
                .build();

        WarrantyResponse onEndDate = WarrantyResponse.from(warranty, LocalDate.of(2026, 10, 13));
        WarrantyResponse nextDate = WarrantyResponse.from(warranty, LocalDate.of(2026, 10, 14));

        assertThat(onEndDate.isExpiryConfirmed()).isTrue();
        assertThat(onEndDate.isExpired()).isFalse();
        assertThat(onEndDate.getDaysUntilExpiry()).isZero();
        assertThat(nextDate.isExpired()).isTrue();
        assertThat(nextDate.getDaysUntilExpiry()).isZero();
    }

    @Test
    void 여러_보증의_응답에_같은_업무일을_적용할_수_있다() {
        DefectWarranty first = warrantyEndingOn(LocalDate.of(2026, 10, 13));
        DefectWarranty second = warrantyEndingOn(LocalDate.of(2026, 10, 14));
        LocalDate businessDay = LocalDate.of(2026, 10, 13);

        assertThat(WarrantyResponse.from(first, businessDay).isExpired()).isFalse();
        assertThat(WarrantyResponse.from(second, businessDay).getDaysUntilExpiry()).isEqualTo(1);
    }

    @Test
    void legacyManualStatusAloneIsNotConfirmationEvidence() {
        DefectWarranty warranty = warrantyEndingOn(LocalDate.of(2026, 10, 14));
        ReflectionTestUtils.setField(warranty, "expiryConfirmedAt", null);

        WarrantyResponse response = WarrantyResponse.from(warranty, LocalDate.of(2026, 10, 13));

        assertThat(response.isExpiryConfirmed()).isFalse();
        assertThat(response.getDaysUntilExpiry()).isZero();
        assertThat(response.getExpiryConfirmedAt()).isNull();
    }

    private DefectWarranty warrantyEndingOn(LocalDate endDate) {
        return DefectWarranty.builder()
                .siteId(1L)
                .insuranceCompany("[QA] 보험사")
                .startDate(LocalDate.of(2026, 10, 1))
                .endDate(endDate)
                .build();
    }
}
