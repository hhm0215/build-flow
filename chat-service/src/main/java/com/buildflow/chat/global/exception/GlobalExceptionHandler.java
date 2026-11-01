package com.buildflow.chat.global.exception;

import com.buildflow.chat.global.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        log.error("BusinessException: {}", e.getMessage());
        return ResponseEntity
                .status(e.getErrorCode().getStatus())
                .body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBindException(BindException e) {
        String message = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(message));
    }

    /**
     * SSE 스트리밍 중 클라이언트 연결 종료(중단/타임아웃) — 응답 불가 상태라 아무것도 쓰지 않는다 (장애 아님).
     * chat-service의 요청 처리는 파일 IO가 없어 IOException은 사실상 끊긴 커넥션 쓰기 실패다.
     */
    @ExceptionHandler({AsyncRequestNotUsableException.class, IOException.class})
    public void handleClientDisconnect(Exception e) {
        log.info("클라이언트 연결 종료로 응답 중단: {}", e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableRequest(HttpMessageNotReadableException e) {
        // 요청 본문 조각이 로그에 남지 않도록 원인 예외 종류만 기록
        log.warn("HttpMessageNotReadableException: causeType={}",
                e.getMostSpecificCause().getClass().getSimpleName());
        return ResponseEntity.badRequest().body(ApiResponse.error("요청 본문이 올바르지 않습니다."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        // 입력값 자체는 응답·로그에 반사하지 않고 파라미터명과 기대 타입만 기록
        Class<?> requiredType = e.getRequiredType();
        log.warn("MethodArgumentTypeMismatchException: name={}, requiredType={}",
                e.getName(), requiredType != null ? requiredType.getSimpleName() : "unknown");
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("요청 값 형식이 올바르지 않습니다: " + e.getName()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException e) {
        log.warn("MissingServletRequestParameterException: name={}", e.getParameterName());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("필수 요청 값이 없습니다: " + e.getParameterName()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("서버 내부 오류가 발생했습니다."));
    }
}
