package com.buildflow.site.domain.dashboard.service;

import com.buildflow.site.domain.dashboard.dto.DashboardStatsResponse;
import com.buildflow.site.domain.dashboard.dto.DashboardStatsResponse.SiteProfitSummary;
import com.buildflow.site.global.exception.BusinessException;
import com.buildflow.site.global.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 대시보드 AI 요약: 모델 응답 처리와 실패를 OLLAMA_API_FAILED 하나로 모으는지 고정한다. 사실 분류는 DashboardSummaryFactsTest. */
class OllamaServiceTest {

    private OllamaService service(ExchangeFunction exchange) {
        WebClient client = WebClient.builder().baseUrl("http://ollama.test").exchangeFunction(exchange).build();
        OllamaService service = new OllamaService(client, new ObjectMapper());
        ReflectionTestUtils.setField(service, "model", "test-model");
        ReflectionTestUtils.setField(service, "timeoutSeconds", 5);
        return service;
    }

    private static ExchangeFunction respond(HttpStatus status, String json) {
        return request -> Mono.just(ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(json)
                .build());
    }

    private static DashboardSummaryFacts stats() {
        return DashboardSummaryFacts.from(rawStats());
    }

    private static DashboardStatsResponse rawStats() {
        return DashboardStatsResponse.builder()
                .totalSites(1)
                .sitesByStatus(Map.of("IN_PROGRESS", 1))
                .totalEstimateAmount(new BigDecimal("1E+7"))
                .totalPurchaseAmount(new BigDecimal("4000000.00"))
                .totalMargin(new BigDecimal("6000000.00"))
                .averageMarginRate(new BigDecimal("60.00"))
                .siteProfits(List.of(SiteProfitSummary.builder()
                        .siteName("[QA] 현장").status("IN_PROGRESS")
                        .estimateAmount(new BigDecimal("1E+7")).purchaseAmount(new BigDecimal("4000000.00"))
                        .margin(new BigDecimal("6000000.00")).marginRate(new BigDecimal("60.00"))
                        .build()))
                .build();
    }

    @Test
    void 모델_응답의_message_content를_요약으로_돌려준다() {
        String summary = service(respond(HttpStatus.OK, "{\"message\":{\"role\":\"assistant\",\"content\":\"## 요약\"},\"done\":true}"))
                .generateSummary(stats());

        assertThat(summary).isEqualTo("## 요약");
    }

    @Test
    void 응답_본문이_비거나_서버_오류이거나_연결이_끊기면_OLLAMA_API_FAILED다() {
        for (ExchangeFunction exchange : List.<ExchangeFunction>of(
                respond(HttpStatus.OK, "{\"done\":true}"),
                respond(HttpStatus.INTERNAL_SERVER_ERROR, "{\"error\":\"model not found\"}"),
                request -> Mono.error(new IllegalStateException("connection reset")))) {
            assertThatThrownBy(() -> service(exchange).generateSummary(stats()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.OLLAMA_API_FAILED);
        }
    }

    @Test
    void 시스템_프롬프트는_사실_외_판단을_금지하고_사실은_로케일_고정_표기다() {
        assertThat(OllamaService.SYSTEM_PROMPT).contains("제공된 사실 외의 판단").contains("견적 미등록")
                .contains("'다음 할 일' 항목만 옮기고 새로 만들지 마세요");
        assertThat(stats().toPrompt())
                .contains("- [QA] 현장 (시공 중): 견적 10,000,000원, 매입 4,000,000원, 마진 6,000,000원, 마진율 60.00%")
                .doesNotContain("E+");
    }
}
