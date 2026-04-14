package com.buildflow.purchase.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Purchase
    PURCHASE_NOT_FOUND(HttpStatus.NOT_FOUND, "매입 내역을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String message;
}
