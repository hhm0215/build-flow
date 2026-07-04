package com.buildflow.chat.domain.chat.service;

import com.buildflow.chat.domain.chat.dto.ChatRequest;
import com.buildflow.chat.domain.chat.dto.ChatResponse;
import com.buildflow.chat.domain.chat.entity.ChatMessage;
import com.buildflow.chat.domain.chat.entity.ChatRole;
import com.buildflow.chat.domain.chat.entity.ChatSession;
import com.buildflow.chat.domain.chat.repository.ChatMessageRepository;
import com.buildflow.chat.domain.chat.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    @Value("${chat.history-limit:20}")
    private int historyLimit;

    @Value("${chat.session-ttl-minutes:30}")
    private long sessionTtlMinutes;

    public ChatResponse chat(ChatRequest request) {
        String sessionId = (request.getSessionId() == null || request.getSessionId().isBlank())
                ? UUID.randomUUID().toString()
                : request.getSessionId();

        if (!sessionRepository.existsById(sessionId)) {
            sessionRepository.save(ChatSession.builder().id(sessionId).build());
        }

        List<Object> messages = buildMessages(sessionId, request.getMessage());

        messageRepository.save(ChatMessage.builder()
                .sessionId(sessionId).role(ChatRole.USER).content(request.getMessage()).build());

        String answer = ollamaToolService.run(messages);

        messageRepository.save(ChatMessage.builder()
                .sessionId(sessionId).role(ChatRole.ASSISTANT).content(answer).build());

        touchSession(sessionId);
        return ChatResponse.of(sessionId, answer);
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
