package com.buildflow.site.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Site
    SITE_NOT_FOUND(HttpStatus.NOT_FOUND, "현장을 찾을 수 없습니다."),
    SITE_ALREADY_COMPLETED(HttpStatus.CONFLICT, "이미 완료된 현장입니다."),

    // Client
    CLIENT_NOT_FOUND(HttpStatus.NOT_FOUND, "거래처를 찾을 수 없습니다."),
    CLIENT_HAS_SITES(HttpStatus.CONFLICT, "해당 거래처에 연결된 현장이 존재합니다.");

    private final HttpStatus status;
    private final String message;
}
