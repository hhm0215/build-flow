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
                        "등록된 모든 현장 목록(id, 이름, 상태)을 조회한다. 현장 이름으로 siteId를 찾을 때 먼저 호출한다. "
                                + "상태: BIDDING=수주 전(계약 전 견적 단계), IN_PROGRESS=시공 중, SETTLING=정산 중, "
                                + "WARRANTY=하자보증, COMPLETED=완료. 답변에는 한국어 상태명을 쓴다.",
                        noParams()),
                tool("getSiteProfit",
                        "특정 현장의 마진, 마진율, 총견적액, 총매입액을 조회한다.",
                        siteIdParam()),
                tool("getOutstandingTax",
                        "특정 현장의 미수금(매출 세금계산서 대비 미입금액)을 조회한다.",
                        siteIdParam()),
                tool("getDashboardSummary",
                        "전체 현장 손익 통계(AI 생성 글이 아닌 집계 숫자)를 조회한다: 전체 현장 수와 상태별 현장 수, 총견적액·총매입액·총마진·마진율 합계(합계와 마진율은 수주 전 BIDDING 현장 제외, 마진율=총마진/총견적액×100), "
                                + "현장별 손익 목록(siteProfits: 현장명, 상태, 견적액, 매입액, 마진, 마진율; 수주 전 현장도 포함, 최근 등록순). 목록이 길면 앞쪽 일부만 넣고 siteProfitsTotalCount로 전체 건수를 알린다.",
                        noParams()),
                tool("listEstimatesBySite",
                        "특정 현장의 견적서 목록(제목, 상태 DRAFT/CONFIRMED/SUPERSEDED, 견적일, 금액)을 조회한다. 손익에는 확정(CONFIRMED) 견적만 반영되고, 수정본에 대체된(SUPERSEDED) 견적은 이력일 뿐 손익에서 빠진다.",
                        siteIdParam()),
                tool("listPurchasesBySite",
                        "특정 현장의 매입 목록(품목, 수량, 단가, 금액, 공급업체, 매입일)을 조회한다.",
                        siteIdParam())
        );
    }
}
