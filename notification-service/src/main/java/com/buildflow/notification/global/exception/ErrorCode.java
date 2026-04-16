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
    WARRANTY_NOT_FOUND(HttpStatus.NOT_FOUND, "하자보증보험을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String message;
}
