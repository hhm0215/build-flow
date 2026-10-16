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
    INVALID_SITE_REFERENCE(HttpStatus.BAD_REQUEST, "유효한 현장을 찾을 수 없습니다."),
    SITE_REFERENCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "현장 확인 서비스를 사용할 수 없습니다."),
    WARRANTY_FILE_EMPTY(HttpStatus.BAD_REQUEST, "업로드된 파일이 비어 있습니다."),
    WARRANTY_INVALID_PDF(HttpStatus.BAD_REQUEST, "유효한 PDF 파일만 업로드할 수 있습니다."),
    WARRANTY_FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "PDF 파일은 20 MiB 이하여야 합니다."),
    WARRANTY_FILE_UNAVAILABLE(HttpStatus.NOT_FOUND, "업로드한 PDF 원본을 사용할 수 없습니다."),
    WARRANTY_FILE_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "PDF 요청이 많습니다. 잠시 후 다시 시도해 주세요."),
    WARRANTY_FILE_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "관리자 인증이 필요합니다."),
    WARRANTY_FILE_STORAGE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "파일 저장에 실패했습니다.");

    private final HttpStatus status;
    private final String message;
}
