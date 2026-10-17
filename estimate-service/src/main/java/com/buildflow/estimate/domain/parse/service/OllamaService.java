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
            log.error("Ollama 연결 실패");
            throw new BusinessException(ErrorCode.OLLAMA_API_FAILED);
        } catch (Exception e) {
            log.error("Ollama API 호출 실패");
            throw new BusinessException(ErrorCode.OLLAMA_API_FAILED);
        }
    }

    /**
     * 모델이 설명·코드블록·"[참고]" 같은 대괄호 문구를 섞어도 실제 항목 배열을 찾도록,
     * 각 '[' 위치부터 마지막 ']'까지를 순서대로 파싱해 처음 성공한 배열을 쓴다.
     */
    private List<ParsedItemResult> parseJsonResponse(String text) {
        if (text == null || text.isBlank()) {
            log.error("Ollama 응답 본문이 비어 있음");
            throw new BusinessException(ErrorCode.OLLAMA_PARSE_FAILED);
        }
        int end = text.lastIndexOf(']');
        for (int start = text.indexOf('['); start != -1 && start < end; start = text.indexOf('[', start + 1)) {
            try {
                return objectMapper.readValue(text.substring(start, end + 1), new TypeReference<>() {});
            } catch (Exception ignored) {
                // 다음 '[' 후보로 계속
            }
        }
        log.error("Ollama 응답에서 항목 JSON 배열을 찾을 수 없음");
        throw new BusinessException(ErrorCode.OLLAMA_PARSE_FAILED);
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
