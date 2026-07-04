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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ollama 툴-콜링 에이전트 루프.
 * LLM이 도구를 고르면 {@link ToolExecutor}로 실행해 결과를 다시 넣고, 도구 호출이 없을 때까지
 * (또는 max 라운드까지) 반복한 뒤 최종 한국어 답변을 반환한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaToolService {

    private final WebClient ollamaWebClient;
    private final ObjectMapper objectMapper;
    private final ToolExecutor toolExecutor;

    @Value("${ollama.api.model}")
    private String model;

    @Value("${ollama.api.timeout}")
    private int timeoutSeconds;

    @Value("${chat.max-tool-rounds:4}")
    private int maxToolRounds;

    /** messages: system/history/user가 채워진 가변 리스트. 루프 중 도구 왕복이 추가된다. */
    public String run(List<Object> messages) {
        for (int round = 0; round < maxToolRounds; round++) {
            JsonNode message = callOllama(messages, ToolCatalog.tools());
            JsonNode toolCalls = message.get("tool_calls");

            if (toolCalls == null || !toolCalls.isArray() || toolCalls.isEmpty()) {
                return textOrFallback(message);
            }

            // 어시스턴트의 도구 호출 메시지를 컨텍스트에 그대로 되돌려 넣는다.
            messages.add(objectMapper.convertValue(message, Map.class));

            for (JsonNode call : toolCalls) {
                JsonNode fn = call.get("function");
                if (fn == null || fn.get("name") == null) {
                    continue;
                }
                String name = fn.get("name").asText();
                JsonNode args = normalizeArgs(fn.get("arguments"));
                String result = toolExecutor.execute(name, args);
                messages.add(Map.of("role", "tool", "content", result));
            }
        }

        // 도구 라운드 상한 초과 → 도구 없이 최종 답변을 강제한다.
        JsonNode finalMsg = callOllama(messages, null);
        return textOrFallback(finalMsg);
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
