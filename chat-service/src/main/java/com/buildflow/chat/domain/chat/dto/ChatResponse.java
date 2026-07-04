package com.buildflow.chat.domain.chat.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ChatResponse {

    private String sessionId;
    private String answer;

    public static ChatResponse of(String sessionId, String answer) {
        return ChatResponse.builder()
                .sessionId(sessionId)
                .answer(answer)
                .build();
    }
}
