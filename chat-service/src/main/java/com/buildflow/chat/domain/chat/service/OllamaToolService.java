package com.buildflow.chat.domain.chat.service;

import com.buildflow.chat.domain.chat.tool.IntentRouter;
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
                if (round == 0 && applyFallback(messages, statusConsumer)) {
                    // 데이터 질문인데 도구 없이 답하려 했다 → 규칙으로 고른 도구 결과를 넣고 다시 답하게 한다.
                    return emitAnswer(textOrFallback(callOllama(messages, null)), statusConsumer, tokenConsumer);
                }
                return emitAnswer(textOrFallback(message), statusConsumer, tokenConsumer);
            }

            // 어시스턴트의 도구 호출 메시지를 컨텍스트에 그대로 되돌려 넣는다.
            messages.add(objectMapper.convertValue(message, Map.class));

            for (JsonNode call : toolCalls) {
                messages.add(toolResultMessage(call, statusConsumer));
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

    /** 마지막 사용자 질문을 규칙 라우터에 넣어 도구를 실행하고 그 왕복을 컨텍스트에 추가한다. 실행했으면 true. */
    boolean applyFallback(List<Object> messages, Consumer<String> statusConsumer) {
        String question = lastUserQuestion(messages);
        List<IntentRouter.SiteRef> sites = IntentRouter.needsSites(question) ? loadSites() : List.of();
        List<IntentRouter.RoutedCall> routed = IntentRouter.route(question, sites);
        if (routed.isEmpty()) {
            return false;
        }
        List<Map<String, Object>> calls = routed.stream().map(IntentRouter::asToolCall).toList();
        Map<String, Object> assistant = new HashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", "");
        assistant.put("tool_calls", calls);
        messages.add(assistant);
        for (Map<String, Object> call : calls) {
            messages.add(toolResultMessage(objectMapper.valueToTree(call), statusConsumer));
        }
        log.info("도구 미선택 질문에 규칙 폴백 적용 tools={}", routed.stream().map(IntentRouter.RoutedCall::tool).toList());
        return true;
    }

    private static String lastUserQuestion(List<Object> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Map<?, ?> message && "user".equals(message.get("role"))) {
                Object content = message.get("content");
                return content == null ? "" : content.toString();
            }
        }
        return "";
    }

    private List<IntentRouter.SiteRef> loadSites() {
        try {
            JsonNode sites = objectMapper.readTree(toolExecutor.execute("listSites", null));
            List<IntentRouter.SiteRef> refs = new ArrayList<>();
            if (sites != null && sites.isArray()) {
                for (JsonNode site : sites) {
                    if (site.hasNonNull("id") && site.hasNonNull("siteName")) {
                        refs.add(new IntentRouter.SiteRef(site.get("id").asLong(), site.get("siteName").asText()));
                    }
                }
            }
            return refs;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 도구 호출 1건마다 결과 메시지 1건을 돌려준다 — 형식이 깨진 호출도 오류 결과로 응답해
     * 호출 수와 결과 수가 어긋나지 않게 한다. 모델이 호출 id를 주면 tool_call_id로,
     * 도구명은 Ollama 기본 필드 tool_name으로 함께 넣어 여러 호출의 결과를 섞지 않게 한다.
     */
    Map<String, Object> toolResultMessage(JsonNode call, Consumer<String> statusConsumer) {
        Map<String, Object> result = new HashMap<>();
        result.put("role", "tool");
        JsonNode id = call == null ? null : call.get("id");
        if (id != null && id.isTextual() && !id.asText().isBlank()) {
            result.put("tool_call_id", id.asText());
        }
        JsonNode fn = call == null ? null : call.get("function");
        JsonNode name = fn == null ? null : fn.get("name");
        if (name == null || !name.isTextual() || name.asText().isBlank()) {
            result.put("content", "{\"error\":\"도구 이름이 없는 호출은 실행하지 않았습니다\"}");
            return result;
        }
        statusConsumer.accept("tool");
        result.put("tool_name", name.asText());
        result.put("content", toolExecutor.execute(name.asText(), normalizeArgs(fn.get("arguments"))));
        return result;
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
