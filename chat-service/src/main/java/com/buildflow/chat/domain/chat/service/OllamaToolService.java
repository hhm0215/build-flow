package com.buildflow.chat.domain.chat.service;

import com.buildflow.chat.domain.chat.tool.ToolCatalog;
import com.buildflow.chat.domain.chat.tool.ToolExecutor;
import com.buildflow.chat.global.exception.BusinessException;
import com.buildflow.chat.global.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ollama 툴-콜링 에이전트 루프.
 * LLM이 도구를 고르면 {@link ToolExecutor}로 실행해 결과를 다시 넣고, 도구 호출이 없을 때까지
 * (또는 max 라운드까지) 반복한 뒤 최종 한국어 답변을 반환한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaToolService {

    private static final int CHUNK_MIN_LENGTH = 16;
    private static final long AVAILABILITY_CACHE_NANOS = TimeUnit.SECONDS.toNanos(5);

    private long availabilityCheckedAtNanos;
    private boolean cachedAvailability;

    private final WebClient ollamaWebClient;
    private final ObjectMapper objectMapper;
    private final ToolExecutor toolExecutor;

    @Value("${ollama.api.model}")
    private String model;

    @Value("${ollama.api.timeout}")
    private int timeoutSeconds;

    @Value("${chat.max-tool-rounds:4}")
    private int maxToolRounds;

    /** 질문을 받기 전에 모델 서버와 설정된 모델이 모두 준비됐는지 짧게 확인한다. */
    public synchronized boolean isAvailable() {
        long now = System.nanoTime();
        if (availabilityCheckedAtNanos != 0 && now - availabilityCheckedAtNanos < AVAILABILITY_CACHE_NANOS) {
            return cachedAvailability;
        }
        cachedAvailability = probeAvailability();
        availabilityCheckedAtNanos = System.nanoTime();
        return cachedAvailability;
    }

    private boolean probeAvailability() {
        try {
            JsonNode response = ollamaWebClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(2))
                    .block();
            JsonNode models = response == null ? null : response.get("models");
            if (models == null || !models.isArray()) {
                return false;
            }
            for (JsonNode entry : models) {
                if (model.equals(entry.path("name").asText())) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("AI 모델 준비 상태 확인 실패: {}", e.getClass().getSimpleName());
        }
        return false;
    }

    /** messages: system/history/user가 채워진 가변 리스트. 루프 중 도구 왕복이 추가된다. */
    public String run(List<Object> messages) {
        return runStreaming(messages, status -> { }, token -> { });
    }

    /**
     * run()과 동일한 에이전트 루프에 진행 상태·답변 조각 콜백을 얹은 버전.
     * 최종 답변은 툴 선택 라운드가 이미 생성한 내용을 그대로 조각내 전달한다 —
     * 별도 재생성 없음(동기 경로와 항상 같은 답변, LLM 호출 1회 절약).
     */
    public String runStreaming(List<Object> messages, Consumer<String> statusConsumer,
                               Consumer<String> tokenConsumer) {
        for (int round = 0; round < maxToolRounds; round++) {
            statusConsumer.accept("thinking");
            JsonNode message = callOllama(messages, ToolCatalog.tools());
            JsonNode toolCalls = message.get("tool_calls");

            if (toolCalls == null || !toolCalls.isArray() || toolCalls.isEmpty()) {
                return emitAnswer(textOrFallback(message), statusConsumer, tokenConsumer);
            }

            // 어시스턴트의 도구 호출 메시지를 컨텍스트에 그대로 되돌려 넣는다.
            messages.add(objectMapper.convertValue(message, Map.class));

            for (JsonNode call : toolCalls) {
                JsonNode fn = call.get("function");
                if (fn == null || fn.get("name") == null) {
                    continue;
                }
                statusConsumer.accept("tool");
                String result = toolExecutor.execute(fn.get("name").asText(), normalizeArgs(fn.get("arguments")));
                messages.add(Map.of("role", "tool", "content", result));
            }
        }

        // 도구 라운드 상한 초과 → 도구 없이 최종 답변을 강제한다.
        return emitAnswer(textOrFallback(callOllama(messages, null)), statusConsumer, tokenConsumer);
    }

    private String emitAnswer(String answer, Consumer<String> statusConsumer, Consumer<String> tokenConsumer) {
        statusConsumer.accept("answering");
        for (String chunk : chunkAnswer(answer)) {
            tokenConsumer.accept(chunk);
        }
        return answer;
    }

    /** 답변을 공백 경계 기준 조각으로 나눈다 — SSE token 이벤트 단위. 조각 연결 = 원문. */
    static List<String> chunkAnswer(String answer) {
        List<String> chunks = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\s*\\S+").matcher(answer);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            buffer.append(matcher.group());
            if (buffer.length() >= CHUNK_MIN_LENGTH) {
                chunks.add(buffer.toString());
                buffer.setLength(0);
            }
        }
        if (buffer.length() > 0) {
            chunks.add(buffer.toString());
        }
        return chunks;
    }

    private JsonNode normalizeArgs(JsonNode arguments) {
        if (arguments == null) {
            return null;
        }
        if (arguments.isTextual()) {
            try {
                return objectMapper.readTree(arguments.asText());
            } catch (Exception e) {
                return null;
            }
        }
        return arguments;
    }

    private String textOrFallback(JsonNode message) {
        if (message != null && message.hasNonNull("content")) {
            String content = message.get("content").asText();
            if (!content.isBlank()) {
                return content;
            }
        }
        return "죄송합니다. 답변을 생성하지 못했습니다. 질문을 조금 더 구체적으로 해주세요.";
    }

    private JsonNode callOllama(List<Object> messages, List<Map<String, Object>> tools) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("messages", messages);
        if (tools != null) {
            body.put("tools", tools);
        }
        try {
            JsonNode response = ollamaWebClient.post()
                    .uri("/api/chat")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            if (response == null || response.get("message") == null) {
                throw new BusinessException(ErrorCode.CHAT_LLM_FAILED);
            }
            return response.get("message");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Ollama 호출 실패", e);
            throw new BusinessException(ErrorCode.CHAT_LLM_FAILED);
        }
    }

}
