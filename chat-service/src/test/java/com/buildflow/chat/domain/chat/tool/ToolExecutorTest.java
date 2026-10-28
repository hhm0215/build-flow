package com.buildflow.chat.domain.chat.tool;

import com.buildflow.chat.infra.feign.EstimateClient;
import com.buildflow.chat.infra.feign.PurchaseClient;
import com.buildflow.chat.infra.feign.SiteClient;
import com.buildflow.chat.infra.feign.TaxClient;
import com.buildflow.chat.infra.feign.dto.DashboardStatsDto;
import com.buildflow.chat.infra.feign.dto.FeignApiResponse;
import com.buildflow.chat.infra.feign.dto.EstimateSummaryDto;
import com.buildflow.chat.infra.feign.dto.ProfitDto;
import com.buildflow.chat.infra.feign.dto.PurchaseSummaryDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class ToolExecutorTest {

    @Mock
    private SiteClient siteClient;

    @Mock
    private TaxClient taxClient;

    @Mock
    private EstimateClient estimateClient;

    @Mock
    private PurchaseClient purchaseClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ToolExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ToolExecutor(siteClient, taxClient, estimateClient, purchaseClient, objectMapper);
    }

    @Test
    void 알수없는_도구는_error_json을_반환한다() {
        String result = executor.execute("unknownTool", null);
        assertThat(result).contains("error").contains("알 수 없는 도구");
    }

    @Test
    void 따옴표가_든_도구_이름도_유효한_error_json으로_감싼다() throws Exception {
        String result = executor.execute("x\",\"admin\":true,\"y", null);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.size()).isEqualTo(1);
        assertThat(json.get("error").asText()).isEqualTo("알 수 없는 도구: x\",\"admin\":true,\"y");
    }

    @Test
    void getSiteProfit은_siteId가_없으면_error를_반환한다() {
        String result = executor.execute("getSiteProfit", null);
        assertThat(result).contains("error").contains("siteId");
    }

    @Test
    void getSiteProfit_정상_호출은_데이터_json을_반환한다() throws Exception {
        ProfitDto dto = new ProfitDto();
        dto.setSiteId(1L);
        dto.setMargin(new BigDecimal("1000"));
        FeignApiResponse<ProfitDto> response = new FeignApiResponse<>();
        response.setData(dto);
        given(siteClient.getProfit(1L)).willReturn(response);

        JsonNode args = objectMapper.readTree("{\"siteId\":1}");
        String result = executor.execute("getSiteProfit", args);

        assertThat(result).contains("\"siteId\":1").contains("\"margin\":1000");
    }

    @Test
    void 타서비스_예외는_삼키고_error_json으로_degrade한다() {
        given(siteClient.listSites()).willThrow(new RuntimeException("service down"));
        String result = executor.execute("listSites", null);
        assertThat(result).contains("error");
    }

    @Test
    void 현장_견적_목록은_건수와_요약_필드를_돌려준다() throws Exception {
        EstimateSummaryDto estimate = new EstimateSummaryDto();
        estimate.setId(5L);
        estimate.setTitle("[QA] 견적");
        estimate.setStatus("CONFIRMED");
        estimate.setTotalAmount(new BigDecimal("5000"));
        FeignApiResponse<List<EstimateSummaryDto>> response = new FeignApiResponse<>();
        response.setData(List.of(estimate));
        given(estimateClient.listBySite(7L)).willReturn(response);

        JsonNode result = objectMapper.readTree(executor.execute("listEstimatesBySite", objectMapper.readTree("{\"siteId\":7}")));

        assertThat(result.get("totalCount").asInt()).isEqualTo(1);
        assertThat(result.get("items").get(0).get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(result.has("note")).isFalse();
    }

    @Test
    void 현장_매입_목록은_30건까지만_넘기고_전체_건수를_알린다() throws Exception {
        List<PurchaseSummaryDto> purchases = IntStream.range(0, 45).mapToObj(i -> {
            PurchaseSummaryDto p = new PurchaseSummaryDto();
            p.setId((long) i);
            p.setItemName("자재" + i);
            return p;
        }).toList();
        FeignApiResponse<List<PurchaseSummaryDto>> response = new FeignApiResponse<>();
        response.setData(purchases);
        given(purchaseClient.listBySite(7L)).willReturn(response);

        JsonNode result = objectMapper.readTree(executor.execute("listPurchasesBySite", objectMapper.readTree("{\"siteId\":7}")));

        assertThat(result.get("totalCount").asInt()).isEqualTo(45);
        assertThat(result.get("items")).hasSize(ToolExecutor.LIST_LIMIT);
        assertThat(result.get("note").asText()).contains("30");
    }

    @Test
    void 현장_목록_도구도_siteId가_없으면_error를_반환한다() {
        assertThat(executor.execute("listEstimatesBySite", null)).contains("error").contains("siteId");
        assertThat(executor.execute("listPurchasesBySite", null)).contains("error").contains("siteId");
    }

    @Test
    void 전체_요약_도구는_결정적_손익_통계를_조회하고_합계를_보존한다() throws Exception {
        given(siteClient.getStats()).willReturn(statsResponse(2));

        JsonNode result = objectMapper.readTree(executor.execute("getDashboardSummary", null));

        then(siteClient).should().getStats();
        assertThat(result.get("totalSites").asInt()).isEqualTo(2);
        assertThat(result.get("sitesByStatus").get("IN_PROGRESS").asInt()).isEqualTo(2);
        assertThat(result.get("totalEstimateAmount").decimalValue()).isEqualByComparingTo("100000");
        assertThat(result.get("totalPurchaseAmount").decimalValue()).isEqualByComparingTo("60000");
        assertThat(result.get("totalMargin").decimalValue()).isEqualByComparingTo("40000");
        assertThat(result.get("averageMarginRate").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(result.get("siteProfitsTotalCount").asInt()).isEqualTo(2);
        assertThat(result.get("siteProfits")).hasSize(2);
        assertThat(result.get("siteProfits").get(0).get("siteName").asText()).isEqualTo("현장0");
        assertThat(result.has("note")).isFalse();
    }

    @Test
    void 전체_요약_도구는_현장별_손익을_30건으로_자르고_합계는_그대로_둔다() throws Exception {
        given(siteClient.getStats()).willReturn(statsResponse(31));

        JsonNode result = objectMapper.readTree(executor.execute("getDashboardSummary", null));

        assertThat(result.get("totalSites").asInt()).isEqualTo(31);
        assertThat(result.get("totalEstimateAmount").decimalValue()).isEqualByComparingTo("100000");
        assertThat(result.get("totalMargin").decimalValue()).isEqualByComparingTo("40000");
        assertThat(result.get("siteProfitsTotalCount").asInt()).isEqualTo(31);
        assertThat(result.get("siteProfits")).hasSize(ToolExecutor.LIST_LIMIT);
        assertThat(result.get("note").asText()).contains("30").contains("siteProfitsTotalCount");
    }

    @Test
    void 전체_요약_도구의_타서비스_예외는_error_json으로_degrade한다() {
        given(siteClient.getStats()).willThrow(new RuntimeException("service down"));
        assertThat(executor.execute("getDashboardSummary", null)).contains("error").contains("getDashboardSummary");
    }

    private static FeignApiResponse<DashboardStatsDto> statsResponse(int siteCount) {
        List<DashboardStatsDto.SiteProfitDto> profits = IntStream.range(0, siteCount).mapToObj(i -> {
            DashboardStatsDto.SiteProfitDto p = new DashboardStatsDto.SiteProfitDto();
            p.setSiteId((long) i);
            p.setSiteName("현장" + i);
            p.setStatus("IN_PROGRESS");
            p.setMargin(new BigDecimal("1000"));
            return p;
        }).toList();
        DashboardStatsDto stats = new DashboardStatsDto();
        stats.setTotalSites(siteCount);
        stats.setSitesByStatus(Map.of("IN_PROGRESS", siteCount));
        stats.setTotalEstimateAmount(new BigDecimal("100000"));
        stats.setTotalPurchaseAmount(new BigDecimal("60000"));
        stats.setTotalMargin(new BigDecimal("40000"));
        stats.setAverageMarginRate(new BigDecimal("40.00"));
        stats.setSiteProfits(profits);
        FeignApiResponse<DashboardStatsDto> response = new FeignApiResponse<>();
        response.setData(stats);
        return response;
    }
}
