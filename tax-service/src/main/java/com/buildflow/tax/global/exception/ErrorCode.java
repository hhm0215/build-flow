package com.buildflow.tax.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // TaxInvoice
    TAX_INVOICE_NOT_FOUND(HttpStatus.NOT_FOUND, "세금계산서를 찾을 수 없습니다."),
    INVALID_TAX_INVOICE_AMOUNT(HttpStatus.BAD_REQUEST, "세금계산서 금액이 허용 범위를 벗어났습니다."),
    INVALID_SITE_REFERENCE(HttpStatus.BAD_REQUEST, "유효한 현장을 찾을 수 없습니다."),
    SITE_REFERENCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "현장 확인 서비스를 사용할 수 없습니다."),
    SITE_ARCHIVED(HttpStatus.BAD_REQUEST, "보관된 현장에는 새 자료를 등록할 수 없습니다. 현장을 복원한 뒤 등록해 주세요."),
    PURCHASE_TAX_INVOICE_PAYMENT_NOT_ALLOWED(
            HttpStatus.CONFLICT, "매입 세금계산서는 입금 확인할 수 없습니다."),
    ALREADY_PAYMENT_CONFIRMED(HttpStatus.CONFLICT, "이미 입금 확인된 세금계산서입니다."),
    PAYMENT_CONFIRMED_TAX_INVOICE_IMMUTABLE(
            HttpStatus.CONFLICT, "입금 확인된 세금계산서는 수정하거나 삭제할 수 없습니다.");

    private final HttpStatus status;
    private final String message;
}
