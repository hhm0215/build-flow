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
    SITE_ARCHIVED_DOCUMENT_LOCKED(HttpStatus.CONFLICT, "보관된 현장의 자료는 현장을 복원한 뒤 수정·삭제할 수 있습니다."),
    SITE_ARCHIVED(HttpStatus.BAD_REQUEST, "보관된 현장에는 새 자료를 등록할 수 없습니다. 현장을 복원한 뒤 등록해 주세요."),
    PURCHASE_TAX_INVOICE_PAYMENT_NOT_ALLOWED(
            HttpStatus.CONFLICT, "매입 세금계산서는 입금 확인할 수 없습니다."),
    ALREADY_PAYMENT_CONFIRMED(HttpStatus.CONFLICT, "이미 입금 확인된 세금계산서입니다."),
    PAYMENT_CONFIRMED_TAX_INVOICE_IMMUTABLE(
            HttpStatus.CONFLICT, "입금 내역이 있는 세금계산서는 수정하거나 삭제할 수 없습니다. 입금을 취소한 뒤 다시 시도해 주세요."),

    TAX_INVOICE_LINKED_TO_PURCHASES(
            HttpStatus.CONFLICT, "매입 항목의 증빙으로 연결된 세금계산서입니다. 매입 화면에서 연결을 해제한 뒤 삭제하거나 구분을 바꿔 주세요."),
    PURCHASE_REFERENCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "매입 증빙 연결 확인 서비스를 사용할 수 없습니다."),

    // TaxPayment
    TAX_PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "입금 내역을 찾을 수 없습니다."),
    INVALID_TAX_PAYMENT_AMOUNT(HttpStatus.BAD_REQUEST, "입금액은 0보다 크고 정수 13자리·소수 2자리 이하여야 합니다."),
    INVALID_TAX_PAYMENT_DATE(HttpStatus.BAD_REQUEST, "입금일은 필수입니다."),
    INVALID_TAX_PAYMENT_MEMO(HttpStatus.BAD_REQUEST, "입금 메모·취소 사유는 500자 이하여야 합니다."),
    TAX_PAYMENT_EXCEEDS_BALANCE(HttpStatus.BAD_REQUEST, "입금액이 남은 미수금을 초과합니다."),
    NO_REMAINING_BALANCE(HttpStatus.BAD_REQUEST, "입금할 잔액이 없습니다."),
    TAX_PAYMENT_ALREADY_CANCELED(HttpStatus.CONFLICT, "이미 취소된 입금 내역입니다.");

    private final HttpStatus status;
    private final String message;
}
