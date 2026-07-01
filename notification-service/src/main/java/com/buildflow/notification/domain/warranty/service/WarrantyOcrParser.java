package com.buildflow.notification.domain.warranty.service;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 보증보험 PDF 추출 텍스트를 정규식으로 파싱.
 * 보험사·증권번호·시작·만료일을 best-effort로 추출 — 실패 필드는 null.
 */
public final class WarrantyOcrParser {

    private WarrantyOcrParser() {}

    /** 알려진 한국 보증·손해보험 발행처 화이트리스트 */
    private static final List<String> KNOWN_INSURERS = List.of(
            "서울보증보험", "한국주택금융공사", "주택도시보증공사",
            "한화손해보험", "삼성화재", "현대해상", "DB손해보험",
            "KB손해보험", "메리츠화재", "롯데손해보험", "흥국화재", "농협손해보험"
    );

    private static final Pattern POLICY_PATTERN = Pattern.compile(
            "(?:보험번호|증권번호|보증번호|증서번호)\\s*[:：]?\\s*([A-Z0-9\\-]{6,30})");

    /**
     * 보증/유효/보험/보장 기간 라벨 — 라벨 자체만 매칭 (group 캡처 없음).
     * 라벨 뒤 window는 {@link Matcher#end()} 기준으로 별도 substring 취함.
     * greedy 캡처로 다음 라벨까지 삼켜 while 순회가 조기 종료되는 문제 방지.
     */
    private static final Pattern PERIOD_LABEL = Pattern.compile(
            "보증기간|유효기간|보험기간|보장기간");

    /** 단일 날짜 — YYYY-MM-DD / YYYY.MM.DD / YYYY년 MM월 DD일 */
    private static final Pattern DATE = Pattern.compile(
            "(\\d{4})\\s*[.\\-년]\\s*(\\d{1,2})\\s*[.\\-월]\\s*(\\d{1,2})\\s*일?");

    // 라벨 뒤 검사 대상 window 크기 (자).
    private static final int LABEL_WINDOW_SIZE = 200;
    // 라벨과 시작일 사이 최대 거리 — 라벨 뒤에 부가 설명/발급일자가 끼어드는 경우 오매칭 방지.
    private static final int LABEL_TO_START_WINDOW = 60;
    // 시작일과 종료일 사이 최대 거리 — "~", "부터~까지" 정도의 짧은 separator만 허용.
    // 임계값을 넘으면 발급일자·유예기간 안내 같은 부가 날짜를 시작일로 오매칭할 위험이 커짐.
    private static final int START_TO_END_GAP = 15;

    public static Result parse(String text) {
        if (text == null || text.isBlank()) {
            return Result.empty();
        }
        String normalized = text.replaceAll("\\s+", " ");
        LocalDate[] period = findPeriod(normalized);
        return Result.builder()
                .insuranceCompany(findInsurer(normalized))
                .policyNumber(findPolicyNumber(normalized))
                .startDate(period[0])
                .endDate(period[1])
                .build();
    }

    private static String findInsurer(String text) {
        for (String name : KNOWN_INSURERS) {
            if (text.contains(name)) return name;
        }
        return null;
    }

    private static String findPolicyNumber(String text) {
        Matcher m = POLICY_PATTERN.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * 보증기간 라벨 뒤에서 등장하는 날짜를 순서대로 추출.
     *
     * <p>거리 제약:
     * <ul>
     *   <li>라벨과 시작일 사이 최대 {@value #LABEL_TO_START_WINDOW}자 — 라벨 직후에 부가 설명·
     *       발급일자 같은 다른 날짜가 끼어드는 경우 오매칭 방지.</li>
     *   <li>시작일과 종료일 사이 최대 {@value #START_TO_END_GAP}자 — 두 날짜 사이가 벌어져
     *       실제로는 서로 다른 문장에 속할 가능성이 높은 경우 매칭 취소.</li>
     * </ul>
     *
     * <p>모든 라벨을 순회하며 두 날짜를 모두 잡은 첫 라벨을 채택. 어느 라벨에서도 두 날짜를 잡지
     * 못했고 종료일이 아예 없는 경우(예: "보증기간 2026-05-01 부터 효력 발생")에만 시작일을
     * fallback으로 반환. gap 초과로 시작일만 잡힌 경우는 오매칭 위험이 커 fallback에서 제외.
     */
    private static LocalDate[] findPeriod(String text) {
        Matcher label = PERIOD_LABEL.matcher(text);
        LocalDate firstStartFallback = null;
        while (label.find()) {
            int windowStart = label.end();
            int windowEnd = Math.min(windowStart + LABEL_WINDOW_SIZE, text.length());
            String window = text.substring(windowStart, windowEnd);
            Matcher startM = DATE.matcher(window);
            if (!startM.find()) continue;
            if (startM.start() > LABEL_TO_START_WINDOW) continue;
            LocalDate start = safeDate(startM.group(1), startM.group(2), startM.group(3));
            if (start == null) continue;

            Matcher endM = DATE.matcher(window);
            boolean hasNextDate = endM.find(startM.end());
            if (hasNextDate) {
                int gap = endM.start() - startM.end();
                if (gap <= START_TO_END_GAP) {
                    LocalDate end = safeDate(endM.group(1), endM.group(2), endM.group(3));
                    if (end != null) return new LocalDate[] { start, end };
                }
                // 두 번째 날짜가 있지만 너무 멀거나 파싱 실패 — 오매칭 위험이 있어 fallback도 skip.
                continue;
            }
            if (firstStartFallback == null) firstStartFallback = start;
        }
        return new LocalDate[] { firstStartFallback, null };
    }

    private static LocalDate safeDate(String y, String mo, String d) {
        try {
            return LocalDate.of(Integer.parseInt(y), Integer.parseInt(mo), Integer.parseInt(d));
        } catch (Exception e) {
            return null;
        }
    }

    @Getter
    @AllArgsConstructor
    @lombok.Builder
    public static class Result {
        private final String insuranceCompany;
        private final String policyNumber;
        private final LocalDate startDate;
        private final LocalDate endDate;

        public static Result empty() {
            return new Result(null, null, null, null);
        }

        public int extractedFieldCount() {
            int n = 0;
            if (insuranceCompany != null) n++;
            if (policyNumber != null) n++;
            if (startDate != null) n++;
            if (endDate != null) n++;
            return n;
        }
    }
}
