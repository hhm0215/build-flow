package com.buildflow.notification.domain.warranty;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** 보증보험의 만료 조회·표시·알림이 공유하는 업무일 기준. */
public final class WarrantyDatePolicy {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private WarrantyDatePolicy() {
    }

    public static LocalDate today() {
        return dateAt(Instant.now());
    }

    static LocalDate dateAt(Instant instant) {
        return instant.atZone(BUSINESS_ZONE).toLocalDate();
    }
}
