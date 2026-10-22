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

/** 대시보드 AI 요약: 모델 응답 처리와 실패를 OLLAMA_API_FAILED 하나로 모으는지, 프롬프트 금액 표기를 고정한다. */
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

    private static DashboardStatsResponse stats() {
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
    void 프롬프트_금액은_지수_표기_없이_원_단위로_적는다() {
        String prompt = ReflectionTestUtils.invokeMethod(service(respond(HttpStatus.OK, "{}")), "buildDataPrompt", stats());

        assertThat(prompt)
                .contains("총 견적액: 10000000원")
                .contains("- [QA] 현장 (시공 중): 견적 10000000원, 매입 4000000.00원, 마진 6000000.00원 (마진율 60.00%)")
                .contains("- 상태별: 시공 중 1개")
                .doesNotContain("E+");
    }

    @Test
    void 상태별_건수는_수주_전부터_한국어_라벨로_적고_모르는_값은_원문을_남긴다() {
        assertThat(OllamaService.statusCounts(Map.of("COMPLETED", 1, "BIDDING", 2, "IN_PROGRESS", 3, "LEGACY", 4)))
                .isEqualTo("수주 전 2개, 시공 중 3개, 완료 1개, LEGACY 4개");
        assertThat(OllamaService.statusCounts(Map.of())).isEqualTo("없음");
    }
}
