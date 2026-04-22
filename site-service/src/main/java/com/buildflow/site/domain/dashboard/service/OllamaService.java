package com.buildflow.site.domain.dashboard.service;

import com.buildflow.site.domain.dashboard.dto.DashboardStatsResponse;
import com.buildflow.site.domain.dashboard.dto.DashboardStatsResponse.SiteProfitSummary;
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
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaService {

    private static final String SYSTEM_PROMPT =
            "당신은 건설 현장 관리 전문가입니다. " +
            "주어진 현장 데이터를 분석하여 한국어로 간결한 경영 요약을 작성하세요. " +
            "포함할 내용: " +
            "1. 전체 현황 요약 (현장 수, 진행 상태) " +
            "2. 손익 분석 (총 마진, 마진율, 수익성 평가) " +
            "3. 주의 현장 (마진율 10% 미만 또는 적자 현장 경고) " +
            "4. 액션 아이템 (구체적 개선 제안 1~3개) " +
            "형식: 마크다운 사용, 핵심만 간결하게. 500자 이내.";

    private final WebClient ollamaWebClient;
    private final ObjectMapper objectMapper;

    @Value("${ollama.api.model}")
    private String model;

    @Value("${ollama.api.timeout}")
    private int timeoutSeconds;

    public String generateSummary(DashboardStatsResponse stats) {
        String dataPrompt = buildDataPrompt(stats);

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "stream", false,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", dataPrompt)
                )
        );

        return callOllama(requestBody);
    }

    private String buildDataPrompt(DashboardStatsResponse stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 현장 데이터\n\n");
        sb.append(String.format("- 전체 현장 수: %d개\n", stats.getTotalSites()));
        sb.append(String.format("- 상태별: %s\n", stats.getSitesByStatus()));
        sb.append(String.format("- 총 견적액: %s원\n", stats.getTotalEstimateAmount().toPlainString()));
        sb.append(String.format("- 총 매입액: %s원\n", stats.getTotalPurchaseAmount().toPlainString()));
        sb.append(String.format("- 총 마진: %s원\n", stats.getTotalMargin().toPlainString()));
        sb.append(String.format("- 평균 마진율: %s%%\n\n", stats.getAverageMarginRate().toPlainString()));

        if (stats.getSiteProfits() != null && !stats.getSiteProfits().isEmpty()) {
            sb.append("## 현장별 손익\n\n");
            for (SiteProfitSummary site : stats.getSiteProfits()) {
                sb.append(String.format("- %s (%s): 견적 %s원, 매입 %s원, 마진 %s원 (마진율 %s%%)\n",
                        site.getSiteName(),
                        site.getStatus(),
                        site.getEstimateAmount().toPlainString(),
                        site.getPurchaseAmount().toPlainString(),
                        site.getMargin().toPlainString(),
                        site.getMarginRate().toPlainString()
                ));
            }
        }

        sb.append("\n위 데이터를 분석하여 경영 요약을 작성해주세요.");
        return sb.toString();
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
