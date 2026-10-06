package com.buildflow.chat.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Chat
    CHAT_MESSAGE_REQUIRED(HttpStatus.BAD_REQUEST, "질문 내용이 비어 있습니다."),
    CHAT_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅 세션을 찾을 수 없습니다."),

    // LLM
    CHAT_LLM_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "AI 모델이 현재 준비되지 않았습니다. 잠시 후 다시 확인해주세요.");

    private final HttpStatus status;
    private final String message;
}
