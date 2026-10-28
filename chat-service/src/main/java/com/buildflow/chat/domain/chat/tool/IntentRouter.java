package com.buildflow.chat.domain.chat.tool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 규칙 기반 폴백 라우터(아키텍처 C).
 * 소형 로컬 모델이 데이터 질문에도 도구를 고르지 않고 답하면 숫자를 지어낼 수 있으므로,
 * 질문의 업무 키워드와 현장 이름으로 실행할 도구를 정해 근거 데이터를 강제로 넣는다.
 */
public final class IntentRouter {

    /** 현장 이름 매칭용 최소 정보. */
    public record SiteRef(long id, String name) {
    }

    /** 실행할 도구 1건. siteId가 없으면 null. */
    public record RoutedCall(String tool, Long siteId) {
    }

    private static final List<String> PROFIT = List.of("마진", "손익", "이익", "수익", "적자");
    private static final List<String> OUTSTANDING = List.of("미수", "미입금", "입금", "받을 돈", "못 받");
    private static final List<String> ESTIMATE = List.of("견적");
    private static final List<String> PURCHASE = List.of("매입", "자재", "구매", "구입");
    private static final List<String> SUMMARY = List.of("전체", "요약", "대시보드", "모든 현장", "현장들");
    private static final List<String> SITE_LIST = List.of("현장 목록", "현장목록", "어떤 현장", "현장 몇", "현장이 몇");

    /** 한 질문에 매칭되는 현장은 이름이 가장 긴 것부터 최대 이 수만큼만 조회한다. */
    static final int MAX_SITES = 3;

    private IntentRouter() {
    }

    /** 현장별 도구가 필요한 질문인지 — 현장 목록 조회 비용을 이때만 낸다. */
    public static boolean needsSites(String question) {
        String q = normalize(question);
        return containsAny(q, PROFIT) || containsAny(q, OUTSTANDING)
                || containsAny(q, ESTIMATE) || containsAny(q, PURCHASE);
    }

    public static List<RoutedCall> route(String question, List<SiteRef> sites) {
        String q = normalize(question);
        List<String> siteTools = new ArrayList<>();
        if (containsAny(q, PROFIT)) {
            siteTools.add("getSiteProfit");
        }
        if (containsAny(q, OUTSTANDING)) {
            siteTools.add("getOutstandingTax");
        }
        if (containsAny(q, ESTIMATE)) {
            siteTools.add("listEstimatesBySite");
        }
        if (containsAny(q, PURCHASE)) {
            siteTools.add("listPurchasesBySite");
        }

        List<RoutedCall> calls = new ArrayList<>();
        List<SiteRef> matched = matchSites(q, sites);
        if (!siteTools.isEmpty() && !matched.isEmpty()) {
            for (SiteRef site : matched) {
                for (String tool : siteTools) {
                    calls.add(new RoutedCall(tool, site.id()));
                }
            }
            return calls;
        }
        boolean money = containsAny(q, PROFIT) || containsAny(q, OUTSTANDING);
        if (containsAny(q, SUMMARY) || money) {
            // 현장을 특정하지 못한 손익·미수 질문은 전체 요약이 가장 가까운 근거다.
            calls.add(new RoutedCall("getDashboardSummary", null));
        }
        if (containsAny(q, SITE_LIST) || !siteTools.isEmpty()) {
            // 현장을 못 찾았으면 목록을 넣어 모델이 어느 현장인지 되묻거나 고를 수 있게 한다.
            calls.add(new RoutedCall("listSites", null));
        }
        return calls;
    }

    /** 질문에 이름이 들어 있는 현장(이름이 긴 것부터 최대 {@link #MAX_SITES}개). */
    public static List<SiteRef> mentionedSites(String question, List<SiteRef> sites) {
        return matchSites(normalize(question), sites);
    }

    static List<SiteRef> matchSites(String normalizedQuestion, List<SiteRef> sites) {
        if (sites == null) {
            return List.of();
        }
        String compactQuestion = normalizedQuestion.replace(" ", "");
        return sites.stream()
                .filter(site -> site.name() != null)
                .filter(site -> {
                    String name = normalize(site.name()).replace(" ", "");
                    return name.length() >= 2 && compactQuestion.contains(name);
                })
                .sorted(Comparator.comparingInt((SiteRef site) -> site.name().length()).reversed())
                .limit(MAX_SITES)
                .toList();
    }

    /** 실행 결과를 넣기 전, 모델 컨텍스트에 남길 어시스턴트 도구 호출 형식. */
    public static Map<String, Object> asToolCall(RoutedCall call) {
        Map<String, Object> arguments = call.siteId() == null ? Map.of() : Map.of("siteId", call.siteId());
        return Map.of("function", Map.of("name", call.tool(), "arguments", arguments));
    }

    private static boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
    }
}
