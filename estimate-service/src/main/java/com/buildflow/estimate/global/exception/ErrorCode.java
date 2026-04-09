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
    ESTIMATE_ITEMS_REQUIRED(HttpStatus.BAD_REQUEST, "견적 항목이 최소 1개 이상 필요합니다."),

    // Parse
    INVALID_FILE_FORMAT(HttpStatus.BAD_REQUEST, "xlsx 형식의 파일만 업로드 가능합니다."),
    EXCEL_PARSE_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "엑셀 파일 파싱에 실패했습니다."),
    OLLAMA_API_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "AI 파싱 서비스에 연결할 수 없습니다. Ollama가 실행 중인지 확인해주세요."),
    OLLAMA_PARSE_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "AI가 공내역서 항목을 추출하지 못했습니다. 파일 형식을 확인해주세요.");

    private final HttpStatus status;
    private final String message;
}
