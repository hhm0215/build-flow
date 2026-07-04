package com.buildflow.chat.domain.chat.tool;

import com.buildflow.chat.infra.feign.SiteClient;
import com.buildflow.chat.infra.feign.TaxClient;
import com.buildflow.chat.infra.feign.dto.FeignApiResponse;
import com.buildflow.chat.infra.feign.dto.ProfitDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ToolExecutorTest {

    @Mock
    private SiteClient siteClient;

    @Mock
    private TaxClient taxClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ToolExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ToolExecutor(siteClient, taxClient, objectMapper);
    }

    @Test
    void 알수없는_도구는_error_json을_반환한다() {
        String result = executor.execute("unknownTool", null);
        assertThat(result).contains("error").contains("알 수 없는 도구");
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
}
