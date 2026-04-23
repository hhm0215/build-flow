package com.buildflow.tax.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // TaxInvoice
    TAX_INVOICE_NOT_FOUND(HttpStatus.NOT_FOUND, "세금계산서를 찾을 수 없습니다."),
    ALREADY_PAYMENT_CONFIRMED(HttpStatus.CONFLICT, "이미 입금 확인된 세금계산서입니다.");

    private final HttpStatus status;
    private final String message;
}
