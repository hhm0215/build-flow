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
    WARRANTY_FILE_EMPTY(HttpStatus.BAD_REQUEST, "업로드된 파일이 비어 있습니다."),
    WARRANTY_FILE_STORAGE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "파일 저장에 실패했습니다.");

    private final HttpStatus status;
    private final String message;
}
