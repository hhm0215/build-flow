package com.buildflow.chat.domain.chat.service;

import com.buildflow.chat.domain.chat.dto.ChatRequest;
import com.buildflow.chat.domain.chat.dto.ChatResponse;
import com.buildflow.chat.domain.chat.entity.ChatMessage;
import com.buildflow.chat.domain.chat.entity.ChatRole;
import com.buildflow.chat.domain.chat.entity.ChatSession;
import com.buildflow.chat.domain.chat.repository.ChatMessageRepository;
import com.buildflow.chat.domain.chat.repository.ChatSessionRepository;
import com.buildflow.chat.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 챗봇 오케스트레이션: 세션/이력 로드 → 툴콜 에이전트 실행 → 답변 영구 저장.
 * LLM 호출(수십 초 가능)을 DB 트랜잭션으로 감싸지 않는다 — 각 save()가 개별 트랜잭션.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final String SYSTEM_PROMPT =
            "너는 건설·시공 소규모 업체의 현장 업무 비서다. 사용자의 질문에 답하려면 제공된 도구를 호출해 " +
            "실제 데이터를 가져와야 한다. 추측하지 말고 도구 결과에 근거해 한국어로 간결하게 답한다. " +
            "현장 이름이 언급되면 listSites로 siteId를 먼저 찾은 뒤 해당 도구를 호출한다. " +
            "금액은 원화(₩)로 표기한다. 도구 결과에 error가 있으면 그 사실을 사용자에게 알린다.";

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final OllamaToolService ollamaToolService;
    private final StringRedisTemplate redisTemplate;
    @Qualifier("chatStreamExecutor")
    private final Executor chatStreamExecutor;

    @Value("${chat.history-limit:20}")
    private int historyLimit;

    @Value("${chat.session-ttl-minutes:30}")
    private long sessionTtlMinutes;

    @Value("${chat.stream-timeout-seconds:180}")
    private long streamTimeoutSeconds;

    public ChatResponse chat(ChatRequest request) {
        String sessionId = prepareSession(request);

        List<Object> messages = buildMessages(sessionId, request.getMessage());

        saveMessage(sessionId, ChatRole.USER, request.getMessage());

        String answer = ollamaToolService.run(messages);

        saveMessage(sessionId, ChatRole.ASSISTANT, answer);

        touchSession(sessionId);
        return ChatResponse.of(sessionId, answer);
    }

    public SseEmitter stream(ChatRequest request) {
        SseEmitter emitter = new SseEmitter(Duration.ofSeconds(streamTimeoutSeconds).toMillis());
        AtomicBoolean disconnected = new AtomicBoolean(false);
        emitter.onTimeout(() -> disconnected.set(true));
        emitter.onError(e -> disconnected.set(true));
        try {
            chatStreamExecutor.execute(() -> streamInternal(request, emitter, disconnected));
        } catch (RejectedExecutionException e) {
            log.warn("채팅 스트림 실행 거부 — executor 포화");
            completeWithErrorEvent(emitter, "CHAT_BUSY", "동시 대화가 많아 응답할 수 없습니다. 잠시 후 다시 시도해주세요.");
        }
        return emitter;
    }

    private void streamInternal(ChatRequest request, SseEmitter emitter, AtomicBoolean disconnected) {
        try {
            String sessionId = prepareSession(request);
            send(emitter, disconnected, "session", Map.of("sessionId", sessionId));
            List<Object> messages = buildMessages(sessionId, request.getMessage());
            saveMessage(sessionId, ChatRole.USER, request.getMessage());

            String answer = ollamaToolService.runStreaming(
                    messages,
                    status -> send(emitter, disconnected, "status", Map.of("phase", status)),
                    token -> send(emitter, disconnected, "token", Map.of("content", token)));

            saveMessage(sessionId, ChatRole.ASSISTANT, answer);
            touchSession(sessionId);
            send(emitter, disconnected, "done", Map.of("sessionId", sessionId));
            emitter.complete();
        } catch (SseDisconnectedException e) {
            // 클라이언트 중단/타임아웃 — 서버 장애 아님. 부분 답변은 저장하지 않는다.
            // 컨테이너가 이미 연결을 정리 중이므로 complete()를 다시 부르지 않는다(중복 디스패치 노이즈 방지).
            log.info("채팅 스트림 클라이언트 종료: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("채팅 스트림 실패: {}", e.getMessage());
            String code = (e instanceof BusinessException be) ? be.getErrorCode().name() : "CHAT_STREAM_FAILED";
            completeWithErrorEvent(emitter, code, "답변을 생성하지 못했습니다. 잠시 후 다시 시도해주세요.");
        }
    }

    private String prepareSession(ChatRequest request) {
        String sessionId = (request.getSessionId() == null || request.getSessionId().isBlank())
                ? UUID.randomUUID().toString()
                : request.getSessionId();
        if (!sessionRepository.existsById(sessionId)) {
            sessionRepository.save(ChatSession.builder().id(sessionId).build());
        }
        return sessionId;
    }

    private void saveMessage(String sessionId, ChatRole role, String content) {
        messageRepository.save(ChatMessage.builder()
                .sessionId(sessionId).role(role).content(content).build());
    }

    private void send(SseEmitter emitter, AtomicBoolean disconnected, String event, Object data) {
        if (disconnected.get()) {
            throw new SseDisconnectedException("SSE 연결이 이미 종료됨");
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception e) {
            disconnected.set(true);
            throw new SseDisconnectedException(e.getMessage());
        }
    }

    private void completeWithErrorEvent(SseEmitter emitter, String code, String message) {
        try {
            emitter.send(SseEmitter.event().name("error")
                    .data(Map.of("code", code, "message", message)));
            emitter.complete();
        } catch (Exception e) {
            completeQuietly(emitter);
        }
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 이미 완료/타임아웃된 emitter — 무시
        }
    }

    /** 클라이언트 연결 종료(중단/타임아웃) 신호 — 서버 장애(error 이벤트 대상)와 구분한다. */
    private static class SseDisconnectedException extends RuntimeException {
        SseDisconnectedException(String message) {
            super(message);
        }
    }

    private List<Object> buildMessages(String sessionId, String userMessage) {
        List<Object> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));

        List<ChatMessage> history = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        int start = Math.max(0, history.size() - historyLimit);
        for (int i = start; i < history.size(); i++) {
            ChatMessage m = history.get(i);
            String role = m.getRole() == ChatRole.USER ? "user" : "assistant";
            messages.add(Map.of("role", role, "content", m.getContent()));
        }

        messages.add(Map.of("role", "user", "content", userMessage));
        return messages;
    }

    private void touchSession(String sessionId) {
        try {
            redisTemplate.opsForValue().set(
                    "chat:session:" + sessionId,
                    Instant.now().toString(),
                    Duration.ofMinutes(sessionTtlMinutes));
        } catch (Exception e) {
            // Redis 장애가 대화 흐름을 막지 않도록 무시 (MySQL이 영구 이력의 진실원)
            log.warn("세션 TTL 갱신 실패 sessionId={}: {}", sessionId, e.getMessage());
        }
    }
}
