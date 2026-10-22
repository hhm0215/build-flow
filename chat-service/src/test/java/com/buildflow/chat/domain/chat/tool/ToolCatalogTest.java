package com.buildflow.chat.domain.chat.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCatalogTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> functionOf(Map<String, Object> tool) {
        return (Map<String, Object>) tool.get("function");
    }

    @Test
    void 도구_6종이_기대한_이름으로_노출된다() {
        List<Map<String, Object>> tools = ToolCatalog.tools();

        Set<String> names = tools.stream()
                .map(t -> (String) functionOf(t).get("name"))
                .collect(Collectors.toSet());

        assertThat(tools).hasSize(6);
        assertThat(names).containsExactlyInAnyOrder(
                "listSites", "getSiteProfit", "getOutstandingTax", "getDashboardSummary",
                "listEstimatesBySite", "listPurchasesBySite");
    }

    @Test
    void listSites_설명은_수주_전을_포함한_현장_상태_라벨을_알려준다() {
        String description = ToolCatalog.tools().stream()
                .map(this::functionOf)
                .filter(f -> "listSites".equals(f.get("name")))
                .map(f -> (String) f.get("description"))
                .findFirst()
                .orElseThrow();

        assertThat(description).contains("BIDDING=수주 전", "IN_PROGRESS=시공 중", "SETTLING=정산 중",
                "WARRANTY=하자보증", "COMPLETED=완료");
    }

    @Test
    @SuppressWarnings("unchecked")
    void siteId_도구는_siteId를_required로_선언한다() {
        List<Map<String, Object>> tools = ToolCatalog.tools();

        Map<String, Object> getProfit = tools.stream()
                .filter(t -> "getSiteProfit".equals(functionOf(t).get("name")))
                .findFirst()
                .orElseThrow();

        Map<String, Object> params = (Map<String, Object>) functionOf(getProfit).get("parameters");
        List<String> required = (List<String>) params.get("required");

        assertThat(required).containsExactly("siteId");
    }
}
