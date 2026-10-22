package com.buildflow.tax.domain.taxinvoice.entity;

public enum TaxPaymentStatus {
    UNPAID,     // 유효 입금 없음
    PARTIAL,    // 일부 입금
    PAID        // 공급가+세액 전액 입금
}
