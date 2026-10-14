package com.buildflow.notification.domain.warranty;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class WarrantyDatePolicyTest {

    @Test
    void UTC_15시가_KST_업무일의_자정이다() {
        assertThat(WarrantyDatePolicy.dateAt(Instant.parse("2026-10-13T14:59:59Z")))
                .isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(WarrantyDatePolicy.dateAt(Instant.parse("2026-10-13T15:00:00Z")))
                .isEqualTo(LocalDate.of(2026, 10, 14));
    }
}
