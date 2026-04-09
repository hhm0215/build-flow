package com.buildflow.estimate.domain.parse.service;

import com.buildflow.estimate.domain.parse.dto.ParsedItemResult;
import com.buildflow.estimate.global.exception.BusinessException;
import com.buildflow.estimate.global.exception.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaService {

    private static final String SYSTEM_PROMPT =
            "You are a Korean construction document parser. " +
            "Extract all line items from a 공내역서 (bill of quantities). " +
            "Rules: extract only actual work items, skip headers/subtotals/totals. " +
            "If amount is missing, calculate: amount = quantity * unitPrice. " +
            "Return ONLY a valid JSON array, no explanation, no markdown code blocks. " +
            "JSON format: [{\"itemName\":\"...\",\"unit\":\"...\",\"quantity\":0,\"unitPrice\":0,\"amount\":0}]";

    private final WebClient ollamaWebClient;
    private final ObjectMapper objectMapper;

    @Value("${ollama.api.model}")
    private String model;

    @Value("${ollama.api.timeout}")
    private int timeoutSeconds;

    public List<ParsedItemResult> parseItems(String excelText) {
        String prompt = "다음 공내역서 데이터에서 항목을 추출해주세요:\n\n" + excelText;

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "stream", false,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", prompt)
                )
        );

        String responseText = callOllama(requestBody);
        return parseJsonResponse(responseText);
    }

    private String callOllama(Map<String, Object> requestBody) {
        try {
            OllamaChatResponse response = ollamaWebClient.post()
                    .uri("/api/chat")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(OllamaChatResponse.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            if (response == null || response.getMessage() == null) {
                throw new BusinessException(ErrorCode.OLLAMA_API_FAILED);
            }
            return response.getMessage().getContent();

        } catch (BusinessException e) {
            throw e;
        } catch (WebClientRequestException e) {
            log.error("Ollama 연결 실패: {}", e.getMessage());
            throw new BusinessException(ErrorCode.OLLAMA_API_FAILED);
        } catch (Exception e) {
            log.error("Ollama API 호출 실패", e);
            throw new BusinessException(ErrorCode.OLLAMA_API_FAILED);
        }
    }

    private List<ParsedItemResult> parseJsonResponse(String text) {
        String json = extractJsonArray(text);
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Ollama 응답 JSON 파싱 실패: {}", text);
            throw new BusinessException(ErrorCode.OLLAMA_PARSE_FAILED);
        }
    }

    private String extractJsonArray(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start == -1 || end == -1 || start > end) {
            log.error("JSON 배열을 찾을 수 없음: {}", text);
            throw new BusinessException(ErrorCode.OLLAMA_PARSE_FAILED);
        }
        return text.substring(start, end + 1);
    }

    @Getter
    private static class OllamaChatResponse {
        private Message message;

        @Getter
        private static class Message {
            private String content;
        }
    }
}
