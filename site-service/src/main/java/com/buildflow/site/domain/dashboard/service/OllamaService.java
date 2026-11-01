package com.buildflow.site.domain.dashboard.service;

import com.buildflow.site.global.exception.BusinessException;
import com.buildflow.site.global.exception.ErrorCode;
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

    static final String SYSTEM_PROMPT =
            "당신은 건설 현장 손익 보고서를 한국어 문장으로 옮기는 도우미입니다. " +
            "사용자 메시지의 '확정 사실'만 사용하세요. 제공된 사실 외의 판단·추정·새 계산을 하지 마세요. " +
            "규칙: " +
            "1. 적자·저마진 경고는 '주의 현장' 목록에 있는 현장에만 하고, 목록이 '없음'이면 경고하지 마세요. " +
            "2. '견적 미등록' 현장은 손실이 아니라 아직 데이터가 없는 현장입니다. 손익·마진율을 평가하지 말고 견적 등록이 필요하다고만 안내하세요. " +
            "견적 등록 안내는 '견적 미등록' 목록에 있는 현장에만 하세요. " +
            "3. '수주 전' 현장은 계약 전 견적이라 손익 평가에서 제외합니다. " +
            "'매입 미입력' 현장은 견적만 있고 매입을 아직 입력하지 않은 현장이라 마진율 계산에서 제외합니다. " +
            "이 현장을 '매입 0원'이나 마진율 100%로 쓰지 말고, 이 현장이 있으면 '주의할 현장이 없다'고 단정하지 마세요. " +
            "4. 금액·비율은 제공된 표기 그대로 옮기고, 용어는 '마진', '마진율'처럼 사실 목록의 한국어 표기를 그대로 쓰세요. " +
            "5. 한국어(한글)로만 쓰고 영어 단어·한자를 섞지 마세요. 현장 이름은 그대로 옮기세요. " +
            "6. '다음 할 일'은 사실 목록의 '다음 할 일' 항목만 옮기고 새로 만들지 마세요. 목록이 '없음'이면 '지금 따로 조치할 일은 없습니다'라고 쓰세요. " +
            "구성: 1. 전체 현황 2. 손익 3. 주의 현장 4. 다음 할 일. " +
            "형식: 마크다운, 핵심만 간결하게, 500자 이내.";

    private final WebClient ollamaWebClient;
    private final ObjectMapper objectMapper;

    @Value("${ollama.api.model}")
    private String model;

    @Value("${ollama.api.timeout}")
    private int timeoutSeconds;

    /** 서버가 분류를 끝낸 사실 목록을 문장으로만 옮기게 한다. */
    public String generateSummary(DashboardSummaryFacts facts) {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "stream", false,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", facts.toPrompt())
                )
        );

        return callOllama(requestBody);
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

    @Getter
    private static class OllamaChatResponse {
        private Message message;

        @Getter
        private static class Message {
            private String content;
        }
    }
}
