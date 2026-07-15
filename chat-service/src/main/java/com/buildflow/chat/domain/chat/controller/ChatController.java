package com.buildflow.chat.domain.chat.controller;

import com.buildflow.chat.domain.chat.dto.ChatRequest;
import com.buildflow.chat.domain.chat.dto.ChatResponse;
import com.buildflow.chat.domain.chat.service.ChatService;
import com.buildflow.chat.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@Valid @RequestBody ChatRequest request) {
        return ResponseEntity.ok(ApiResponse.success(chatService.chat(request)));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        return chatService.stream(request);
    }
}
