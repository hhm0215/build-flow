package com.buildflow.chat.domain.chat.tool;

import java.util.List;
import java.util.Map;

/**
 * LLM에 전달하는 도구 카탈로그 (Ollama/OpenAI function 포맷).
 * 각 도구는 {@link ToolExecutor}가 OpenFeign으로 실행한다.
 */
public final class ToolCatalog {

    private ToolCatalog() {
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> params) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", description,
                        "parameters", params
                )
        );
    }

    private static Map<String, Object> noParams() {
        return Map.of("type", "object", "properties", Map.of());
    }

    private static Map<String, Object> siteIdParam() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "siteId", Map.of("type", "integer", "description", "현장 ID. 모르면 listSites로 먼저 확인한다.")
                ),
                "required", List.of("siteId")
        );
    }

    public static List<Map<String, Object>> tools() {
        return List.of(
                tool("listSites",
                        "등록된 모든 현장 목록(id, 이름, 상태)을 조회한다. 현장 이름으로 siteId를 찾을 때 먼저 호출한다.",
                        noParams()),
                tool("getSiteProfit",
                        "특정 현장의 마진, 마진율, 총견적액, 총매입액을 조회한다.",
                        siteIdParam()),
                tool("getOutstandingTax",
                        "특정 현장의 미수금(매출 세금계산서 대비 미입금액)을 조회한다.",
                        siteIdParam()),
                tool("getDashboardSummary",
                        "전체 현장에 대한 AI 요약 대시보드를 조회한다.",
                        noParams())
        );
    }
}
