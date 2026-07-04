package com.buildflow.chat.domain.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class ChatRequest {

    /** 이어지는 대화면 기존 sessionId, 새 대화면 null */
    private String sessionId;

    @NotBlank(message = "질문 내용이 비어 있습니다.")
    private String message;
}
