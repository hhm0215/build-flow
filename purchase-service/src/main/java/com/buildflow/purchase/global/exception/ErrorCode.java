package com.buildflow.purchase.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Purchase
    PURCHASE_NOT_FOUND(HttpStatus.NOT_FOUND, "매입 내역을 찾을 수 없습니다."),
    INVALID_SITE_REFERENCE(HttpStatus.BAD_REQUEST, "유효한 현장을 찾을 수 없습니다."),
    SITE_REFERENCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "현장 확인 서비스를 사용할 수 없습니다."),
    SITE_ARCHIVED(HttpStatus.BAD_REQUEST, "보관된 현장에는 새 자료를 등록할 수 없습니다. 현장을 복원한 뒤 등록해 주세요."),
    INVALID_PURCHASE_AMOUNT(HttpStatus.BAD_REQUEST, "매입 금액이 허용 범위를 벗어났습니다.");

    private final HttpStatus status;
    private final String message;
}
