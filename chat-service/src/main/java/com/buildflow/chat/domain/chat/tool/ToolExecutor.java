package com.buildflow.chat.domain.chat.tool;

import com.buildflow.chat.infra.feign.EstimateClient;
import com.buildflow.chat.infra.feign.PurchaseClient;
import com.buildflow.chat.infra.feign.SiteClient;
import com.buildflow.chat.infra.feign.TaxClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LLM이 선택한 도구를 OpenFeign으로 실행하고 결과를 JSON 문자열로 반환한다.
 * 실패(현장 없음/타 서비스 다운 등)는 예외를 던지지 않고 error JSON을 반환해
 * LLM이 그 사실을 바탕으로 답변하도록 한다(graceful degrade).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolExecutor {

    private static final Set<String> KNOWN = Set.of("listSites", "getSiteProfit", "getOutstandingTax",
            "getDashboardSummary", "listEstimatesBySite", "listPurchasesBySite");
    /** 모델 컨텍스트를 넘치지 않게 목록 도구는 앞쪽 일부만 넘기고 전체 건수를 함께 알린다. */
    static final int LIST_LIMIT = 30;

    private final SiteClient siteClient;
    private final TaxClient taxClient;
    private final EstimateClient estimateClient;
    private final PurchaseClient purchaseClient;
    private final ObjectMapper objectMapper;

    public String execute(String name, JsonNode args) {
        if (!KNOWN.contains(name)) {
            return "{\"error\":\"알 수 없는 도구: " + name + "\"}";
        }
        try {
            Object data = switch (name) {
                case "listSites" -> siteClient.listSites().getData();
                case "getSiteProfit" -> siteClient.getProfit(requireSiteId(args)).getData();
                case "getOutstandingTax" -> taxClient.getOutstanding(requireSiteId(args)).getData();
                case "getDashboardSummary" -> siteClient.getSummary().getData();
                case "listEstimatesBySite" -> limited(estimateClient.listBySite(requireSiteId(args)).getData());
                case "listPurchasesBySite" -> limited(purchaseClient.listBySite(requireSiteId(args)).getData());
                default -> null;
            };
            return objectMapper.writeValueAsString(data);
        } catch (IllegalArgumentException e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        } catch (Exception e) {
            log.warn("도구 실행 실패 name={}: {}", name, e.getMessage());
            return "{\"error\":\"도구 실행에 실패했습니다: " + name + "\"}";
        }
    }

    private static Map<String, Object> limited(List<?> items) {
        List<?> all = items == null ? List.of() : items;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCount", all.size());
        result.put("items", all.subList(0, Math.min(all.size(), LIST_LIMIT)));
        if (all.size() > LIST_LIMIT) {
            result.put("note", "앞쪽 " + LIST_LIMIT + "건만 포함했다. 전체 건수는 totalCount다.");
        }
        return result;
    }

    private Long requireSiteId(JsonNode args) {
        if (args == null || !args.hasNonNull("siteId")) {
            throw new IllegalArgumentException("siteId가 필요합니다. listSites로 현장 ID를 먼저 확인하세요.");
        }
        return args.get("siteId").asLong();
    }
}
