package com.buildflow.chat.domain.chat.service;

import com.buildflow.chat.domain.chat.dto.ChatRequest;
import com.buildflow.chat.domain.chat.entity.ChatMessage;
import com.buildflow.chat.domain.chat.entity.ChatRole;
import com.buildflow.chat.domain.chat.repository.ChatMessageRepository;
import com.buildflow.chat.domain.chat.repository.ChatSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatServiceTest {

    private ChatSessionRepository sessionRepository;
    private ChatMessageRepository messageRepository;
    private OllamaToolService ollamaToolService;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ChatSessionRepository.class);
        messageRepository = mock(ChatMessageRepository.class);
        ollamaToolService = mock(OllamaToolService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        Executor directExecutor = Runnable::run;
        chatService = new ChatService(sessionRepository, messageRepository, ollamaToolService,
                redisTemplate, directExecutor);
        ReflectionTestUtils.setField(chatService, "historyLimit", 20);
        ReflectionTestUtils.setField(chatService, "sessionTtlMinutes", 30L);
        ReflectionTestUtils.setField(chatService, "streamTimeoutSeconds", 180L);
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
    }

    @Test
    void streamPersistsUserAndCompletedAssistantOnce() {
        ChatRequest request = request("현장 마진 알려줘");
        when(ollamaToolService.runStreaming(anyList(), any(), any())).thenAnswer(invocation -> {
            invocation.<java.util.function.Consumer<String>>getArgument(1).accept("answering");
            invocation.<java.util.function.Consumer<String>>getArgument(2).accept("마진은 ");
            invocation.<java.util.function.Consumer<String>>getArgument(2).accept("0원입니다.");
            return "마진은 0원입니다.";
        });

        chatService.stream(request);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(ChatMessage::getRole)
                .containsExactly(ChatRole.USER, ChatRole.ASSISTANT);
        assertThat(captor.getAllValues().get(1).getContent()).isEqualTo("마진은 0원입니다.");
    }

    @Test
    void streamDoesNotPersistAssistantWhenGenerationFails() {
        ChatRequest request = request("현장 마진 알려줘");
        when(ollamaToolService.runStreaming(anyList(), any(), any()))
                .thenThrow(new IllegalStateException("ollama failed"));

        chatService.stream(request);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(ChatRole.USER);
    }

    private ChatRequest request(String message) {
        ChatRequest request = new ChatRequest();
        ReflectionTestUtils.setField(request, "message", message);
        return request;
    }
}
