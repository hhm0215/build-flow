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

    /** 보증기간/유효기간 라벨 후 두 날짜 — YYYY-MM-DD / YYYY.MM.DD / YYYY년 MM월 DD일 */
    private static final Pattern PERIOD_PATTERN = Pattern.compile(
            "(?:보증기간|유효기간|보험기간|보장기간)[\\s\\S]{0,80}?" +
                    "(\\d{4})\\s*[.\\-년]\\s*(\\d{1,2})\\s*[.\\-월]\\s*(\\d{1,2})\\s*일?" +
                    "[\\s~∼\\-까지부터]{1,10}?" +
                    "(\\d{4})\\s*[.\\-년]\\s*(\\d{1,2})\\s*[.\\-월]\\s*(\\d{1,2})\\s*일?");

    public static Result parse(String text) {
        if (text == null || text.isBlank()) {
            return Result.empty();
        }
        String normalized = text.replaceAll("\\s+", " ");
        return Result.builder()
                .insuranceCompany(findInsurer(normalized))
                .policyNumber(findPolicyNumber(normalized))
                .startDate(findStart(normalized))
                .endDate(findEnd(normalized))
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

    private static LocalDate findStart(String text) {
        Matcher m = PERIOD_PATTERN.matcher(text);
        if (!m.find()) return null;
        return safeDate(m.group(1), m.group(2), m.group(3));
    }

    private static LocalDate findEnd(String text) {
        Matcher m = PERIOD_PATTERN.matcher(text);
        if (!m.find()) return null;
        return safeDate(m.group(4), m.group(5), m.group(6));
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
