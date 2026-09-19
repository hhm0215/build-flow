package com.buildflow.notification.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Notification
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),

    // DefectWarranty
    WARRANTY_NOT_FOUND(HttpStatus.NOT_FOUND, "하자보증보험을 찾을 수 없습니다."),
    WARRANTY_OCR_PENDING(HttpStatus.CONFLICT, "AI 분석이 끝난 뒤 수정할 수 있습니다."),
    WARRANTY_INVALID_PERIOD(HttpStatus.BAD_REQUEST, "보증 종료일은 시작일보다 빠를 수 없습니다."),
    WARRANTY_INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "보증금액은 0 이상이어야 합니다."),
    WARRANTY_FILE_EMPTY(HttpStatus.BAD_REQUEST, "업로드된 파일이 비어 있습니다."),
    WARRANTY_FILE_STORAGE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "파일 저장에 실패했습니다.");

    private final HttpStatus status;
    private final String message;
}
