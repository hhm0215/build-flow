package com.buildflow.estimate.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Estimate
    ESTIMATE_NOT_FOUND(HttpStatus.NOT_FOUND, "견적서를 찾을 수 없습니다."),
    ESTIMATE_ALREADY_CONFIRMED(HttpStatus.CONFLICT, "이미 확정된 견적서입니다."),
    ESTIMATE_ITEMS_REQUIRED(HttpStatus.BAD_REQUEST, "견적 항목이 최소 1개 이상 필요합니다.");

    private final HttpStatus status;
    private final String message;
}
