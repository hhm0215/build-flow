package com.buildflow.site.domain.dashboard.service;

import com.buildflow.site.domain.site.entity.SiteStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 대시보드 AI 요약의 모델 출력을 사실 목록과 대조한다. 하나라도 걸리면 호출자가 서버 결정 문구로 바꾼다.
 *
 * <ul>
 *   <li>외국 문자: 한글 문장에 라틴·한자·키릴·가나·태국 문자 덩어리가 섞임("마argin율"). 사실 목록(현장명·상태 원문 등)에
 *       원래 있던 덩어리와 {@link #ALLOWED_FOREIGN_TOKENS}는 허용한다.</li>
 *   <li>모르는 현장명: 알려진 현장명을 지운 뒤에도 "OO현장"처럼 붙은 이름이나 "OO (시공 중)"처럼 상태 라벨이 붙은 이름이 남음.</li>
 *   <li>새 숫자: 원·% 단위 숫자가 사실 목록의 값(부호 무시)과 다르거나 만·억 같은 단위로 바뀜("8,000만 원").</li>
 *   <li>할 일 모순: 서버가 정한 할 일이 있는데 "조치할 일은 없습니다"를 덧붙임.</li>
 *   <li>매입 미입력 오해: 매입 미입력 현장이 있는데 "매입 0원"으로 옮기거나 "주의할 현장이 없습니다"로 단정.</li>
 * </ul>
 * 결과에는 위반 종류만 담는다. 모델 출력 원문·토큰은 사용자 입력이 섞일 수 있어 담지 않는다.
 */
final class SummaryOutputGuard {

    static final String FOREIGN_SCRIPT = "외국 문자 혼입";
    static final String UNKNOWN_SITE = "사실 목록에 없는 현장명";
    static final String NEW_NUMBER = "사실 목록에 없는 금액·비율";
    static final String CONTRADICTION = "할 일 모순";
    static final String EMPTY = "빈 출력";
    static final String NO_PURCHASE_MISREAD = "매입 미입력 오해";

    static final Set<String> ALLOWED_FOREIGN_TOKENS = Set.of("AI");

    private static final Pattern FOREIGN_TOKEN = Pattern.compile(
            "\\p{IsLatin}+|\\p{IsHan}+|\\p{IsCyrillic}+|\\p{IsHiragana}+|\\p{IsKatakana}+|\\p{IsThai}+");

    /** 단위가 붙은 숫자. 만·억·천이 끼면 사실 목록 표기(원 단위 쉼표)와 다르므로 항상 새 숫자로 본다. */
    private static final Pattern UNIT_NUMBER = Pattern.compile(
            "(\\d[\\d,]*(?:\\.\\d+)?)\\s*(천|만|억|조)?\\s*(원|%|퍼센트)");

    private static final String SITE_PLACEHOLDER = "";

    /** "OO현장"에 붙어도 현장명이 아닌 일반 낱말. */
    private static final Set<String> GENERIC_SITE_PREFIXES = Set.of(
            "전체", "각", "모든", "해당", "주의", "대상", "나머지", "다른", "일부", "여러", "두", "세", "네", "기존",
            "신규", "정상", "적자", "저마진", "평가", "미등록", "미입력", "수주", "시공", "정산", "완료", "등록된", "그", "이");

    private static final Pattern ATTACHED_SITE = Pattern.compile("([\\p{L}\\p{N}\\[\\]_-]+)현장");

    private static final Pattern STATUS_LABELED = Pattern.compile(
            "([^\\n:,()*#\\-]+?)\\s*\\((?:" + Arrays.stream(SiteStatus.values())
                    .map(s -> Pattern.quote(s.getLabel()))
                    .collect(Collectors.joining("|")) + ")\\)");

    private static final Pattern NO_ACTION = Pattern.compile("조치할\\s*(?:일|사항)[은이가]?\\s*없");

    /** 매입 미입력 현장이 있는데 "주의할 현장이 없습니다"로 단정. */
    private static final Pattern NO_WARNING = Pattern.compile("주의할\\s*현장[은이가]?\\s*없");

    /** 매입 미입력을 "매입 0원"으로 옮김. 미입력은 0원 매입이 아니다. */
    private static final Pattern ZERO_PURCHASE = Pattern.compile("매입(?:\\s*(?:금액|합계))?[은는이가]?\\s*0\\s*원");

    private SummaryOutputGuard() {
    }

    /** 위반 종류 목록. 비어 있으면 모델 출력을 그대로 써도 된다. */
    static List<String> violations(String output, DashboardSummaryFacts facts) {
        if (output == null || output.isBlank()) {
            return List.of(EMPTY);
        }
        String factText = facts.toPrompt();
        Set<String> violations = new LinkedHashSet<>();
        if (hasForeignToken(output, factText)) {
            violations.add(FOREIGN_SCRIPT);
        }
        if (hasUnknownSite(output, facts.siteNames())) {
            violations.add(UNKNOWN_SITE);
        }
        if (hasNewNumber(output, factText)) {
            violations.add(NEW_NUMBER);
        }
        if (!facts.nextActions().isEmpty() && NO_ACTION.matcher(output).find()) {
            violations.add(CONTRADICTION);
        }
        if (!facts.noPurchaseSites().isEmpty()
                && (NO_WARNING.matcher(output).find() || ZERO_PURCHASE.matcher(output).find())) {
            violations.add(NO_PURCHASE_MISREAD);
        }
        return List.copyOf(violations);
    }

    private static boolean hasForeignToken(String output, String factText) {
        Set<String> allowed = new HashSet<>(ALLOWED_FOREIGN_TOKENS);
        Matcher factTokens = FOREIGN_TOKEN.matcher(factText);
        while (factTokens.find()) {
            allowed.add(factTokens.group());
        }
        Matcher tokens = FOREIGN_TOKEN.matcher(output);
        while (tokens.find()) {
            if (!allowed.contains(tokens.group())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUnknownSite(String output, List<String> siteNames) {
        String rest = output;
        // 긴 이름부터 지워 "A현장"이 "A현장2"의 일부를 먼저 지우지 않게 한다.
        List<String> names = new ArrayList<>(siteNames);
        names.sort(Comparator.comparingInt(String::length).reversed());
        for (String name : names) {
            rest = rest.replace(name, SITE_PLACEHOLDER);
        }
        Matcher attached = ATTACHED_SITE.matcher(rest);
        while (attached.find()) {
            String prefix = attached.group(1);
            if (!GENERIC_SITE_PREFIXES.contains(prefix) && !prefix.matches("\\d+개?")) {
                return true;
            }
        }
        Matcher labeled = STATUS_LABELED.matcher(rest);
        while (labeled.find()) {
            if (!labeled.group(1).strip().endsWith(SITE_PLACEHOLDER)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNewNumber(String output, String factText) {
        Set<String> knownWon = new HashSet<>();
        Set<String> knownPercent = new HashSet<>();
        Matcher facts = UNIT_NUMBER.matcher(factText);
        while (facts.find()) {
            (isPercent(facts.group(3)) ? knownPercent : knownWon).add(normalize(facts.group(1)));
        }
        Matcher numbers = UNIT_NUMBER.matcher(output);
        while (numbers.find()) {
            if (numbers.group(2) != null) {
                return true;
            }
            Set<String> known = isPercent(numbers.group(3)) ? knownPercent : knownWon;
            if (!known.contains(normalize(numbers.group(1)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPercent(String unit) {
        return !"원".equals(unit);
    }

    /** 쉼표·뒤쪽 0을 뺀 값. 부호는 패턴에서 이미 빠져 "마진 200,000원 적자"도 사실 -200,000원과 같다고 본다. */
    private static String normalize(String number) {
        try {
            return new BigDecimal(number.replace(",", "")).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return number;
        }
    }
}
