package com.buildflow.chat.domain.chat.tool;

import com.buildflow.chat.infra.feign.EstimateClient;
import com.buildflow.chat.infra.feign.PurchaseClient;
import com.buildflow.chat.infra.feign.SiteClient;
import com.buildflow.chat.infra.feign.TaxClient;
import com.buildflow.chat.infra.feign.dto.DashboardStatsDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Iterator;
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
    /** siteId 인자로 특정 현장을 조회하는 도구. */
    private static final Set<String> SITE_TOOLS = Set.of("getSiteProfit", "getOutstandingTax",
            "listEstimatesBySite", "listPurchasesBySite");
    /** 모델 컨텍스트를 넘치지 않게 목록 도구는 앞쪽 일부만 넘기고 전체 건수를 함께 알린다. */
    static final int LIST_LIMIT = 30;

    private final SiteClient siteClient;
    private final TaxClient taxClient;
    private final EstimateClient estimateClient;
    private final PurchaseClient purchaseClient;
    private final ObjectMapper objectMapper;

    public String execute(String name, JsonNode args) {
        if (!KNOWN.contains(name)) {
            return error("알 수 없는 도구: " + name);
        }
        try {
            Object data = switch (name) {
                case "listSites" -> siteClient.listSites().getData();
                case "getSiteProfit" -> siteClient.getProfit(requireSiteId(args)).getData();
                case "getOutstandingTax" -> taxClient.getOutstanding(requireSiteId(args)).getData();
                case "getDashboardSummary" -> dashboardStats(siteClient.getStats().getData());
                case "listEstimatesBySite" -> limited(estimateClient.listBySite(requireSiteId(args)).getData());
                case "listPurchasesBySite" -> limited(purchaseClient.listBySite(requireSiteId(args)).getData());
                default -> null;
            };
            return objectMapper.writeValueAsString(formatMoney(objectMapper.valueToTree(data)));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (FeignException.NotFound e) {
            log.warn("도구 404 name={}", name);
            if (SITE_TOOLS.contains(name)) {
                // 모델이 listSites 없이 siteId를 추측하면 404가 난다. 다음 라운드에서 바로잡도록 방법을 알린다.
                return error("siteId=" + requireSiteId(args) + " 현장을 찾을 수 없습니다. listSites로 현장 ID를 확인한 뒤 다시 호출하세요.");
            }
            return error("도구 실행에 실패했습니다: " + name);
        } catch (Exception e) {
            // Feign 예외 메시지에는 상류 응답 본문이 섞일 수 있어 종류만 남긴다.
            log.warn("도구 실행 실패 name={}: {}", name, e.getClass().getSimpleName());
            return error("도구 실행에 실패했습니다: " + name);
        }
    }

    /** 금액이 아닌 원 단위 이름(마진·단가)과 *Amount 필드는 금액, *Rate 필드는 백분율로 본다. */
    private static final Set<String> MONEY_FIELDS = Set.of("margin", "totalMargin", "unitPrice");

    /**
     * 소형 모델이 8000.00 같은 숫자를 "8,000만 원"·"8,000,000원"으로 부풀려 읽는 일이 실측돼(2026-10-28),
     * 금액·비율 숫자를 "8,000원"·"84.00%" 문자열로 미리 만들어 모델이 그대로 옮겨 적게 한다.
     */
    static JsonNode formatMoney(JsonNode node) {
        if (node instanceof ArrayNode array) {
            array.forEach(ToolExecutor::formatMoney);
        } else if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                String name = field.getKey();
                if (value.isNumber() && isMoney(name)) {
                    field.setValue(object.textNode(won(value.decimalValue())));
                } else if (value.isNumber() && name.endsWith("Rate")) {
                    field.setValue(object.textNode(value.decimalValue().setScale(2, RoundingMode.HALF_UP).toPlainString() + "%"));
                } else {
                    formatMoney(value);
                }
            }
        }
        return node;
    }

    private static boolean isMoney(String name) {
        return MONEY_FIELDS.contains(name) || name.endsWith("Amount");
    }

    private static String won(BigDecimal amount) {
        BigDecimal plain = amount.stripTrailingZeros();
        String pattern = plain.scale() > 0 ? "#,##0.##" : "#,##0";
        // 컨테이너 기본 로케일과 무관하게 쉼표 구분·점 소수점으로 고정한다.
        return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ROOT)).format(plain) + "원";
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

    /**
     * 전체 손익 통계는 합계 필드를 그대로 두고 현장별 목록만 {@link #LIST_LIMIT}건으로 자른다.
     * 합계는 site-service가 전체 현장으로 계산한 값이라 목록을 잘라도 정확하다.
     */
    private static Map<String, Object> dashboardStats(DashboardStatsDto stats) {
        if (stats == null) {
            return null;
        }
        List<DashboardStatsDto.SiteProfitDto> all = stats.getSiteProfits() == null ? List.of() : stats.getSiteProfits();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalSites", stats.getTotalSites());
        result.put("sitesByStatus", stats.getSitesByStatus());
        result.put("totalEstimateAmount", stats.getTotalEstimateAmount());
        result.put("totalPurchaseAmount", stats.getTotalPurchaseAmount());
        result.put("totalMargin", stats.getTotalMargin());
        result.put("averageMarginRate", stats.getAverageMarginRate());
        result.put("totalsNote", "합계·마진율은 수주 전(BIDDING) 현장을 제외한 값이다. 현장별 목록에는 수주 전 현장도 포함된다.");
        result.put("siteProfitsTotalCount", all.size());
        result.put("siteProfits", all.subList(0, Math.min(all.size(), LIST_LIMIT)));
        if (all.size() > LIST_LIMIT) {
            result.put("note", "현장별 손익은 최근 등록순 앞쪽 " + LIST_LIMIT
                    + "건만 포함했다. 전체 건수는 siteProfitsTotalCount이며 합계는 전체 현장 기준이다.");
        }
        return result;
    }

    private Long requireSiteId(JsonNode args) {
        if (args == null || !args.hasNonNull("siteId")) {
            throw new IllegalArgumentException("siteId가 필요합니다. listSites로 현장 ID를 먼저 확인하세요.");
        }
        return args.get("siteId").asLong();
    }

    /** 모델이 보낸 도구 이름 등 임의 문자열이 JSON 구조를 깨지 않도록 직렬화기로 감싼다. */
    private String error(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\":\"도구 결과를 만들지 못했습니다\"}";
        }
    }
}
