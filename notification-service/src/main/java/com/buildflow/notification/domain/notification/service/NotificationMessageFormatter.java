package com.buildflow.notification.domain.notification.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 알림 문구 생성 순수 함수 모음.
 * 이름 필드가 없는 과거·재생 이벤트는 기존 "(ID: n)" 문구로 폴백한다.
 * 사용자 입력(현장명·제목·품목·거래처·보험사)은 제어/서식 문자를 제거하고 {@link #MAX_LABEL_LENGTH}자로 말줄임한다.
 */
public final class NotificationMessageFormatter {

    public static final int MAX_LABEL_LENGTH = 50;
    private static final String ELLIPSIS = "…";

    private NotificationMessageFormatter() {
    }

    public static String estimateConfirmed(String siteName, long estimateId, String title) {
        return sitePrefix(siteName) + "견적서(" + detail(estimateId, title) + ")가 확정되었습니다.";
    }

    public static String estimateDeleted(String siteName, long estimateId, String title) {
        return sitePrefix(siteName) + "견적서(" + detail(estimateId, title) + ")가 삭제되었습니다.";
    }

    public static String estimateSuperseded(String siteName, long estimateId, String title, long supersededById) {
        return sitePrefix(siteName) + "견적서(" + detail(estimateId, title) + ")가 수정본(ID: "
                + supersededById + ") 확정으로 대체되었습니다.";
    }

    /** action: "등록" / "수정" / "삭제" */
    public static String purchase(String action, String siteName, long purchaseId, String itemName, String supplier) {
        return sitePrefix(siteName) + "매입(" + detail(purchaseId, itemName, supplier) + ")이 " + action + "되었습니다.";
    }

    public static String taxRegistered(String siteName, boolean sales, long taxInvoiceId,
                                       String counterparty, BigDecimal totalAmount) {
        return sitePrefix(siteName) + (sales ? "매출" : "매입") + " 세금계산서("
                + detail(taxInvoiceId, counterparty, formatAmount(totalAmount)) + ")가 등록되었습니다.";
    }

    public static String taxPaymentConfirmed(String siteName, long taxInvoiceId,
                                             String counterparty, BigDecimal totalAmount) {
        return sitePrefix(siteName) + "세금계산서("
                + detail(taxInvoiceId, counterparty, formatAmount(totalAmount)) + ") 입금이 확인되었습니다.";
    }

    public static String warrantyExpiring(String siteName, long warrantyId, String insuranceCompany,
                                          long daysUntilExpiry) {
        String company = sanitize(insuranceCompany);
        return sitePrefix(siteName) + "하자보증보험(ID: " + warrantyId
                + (company == null ? "" : ", " + company) + ") 만료까지 " + daysUntilExpiry + "일 남았습니다.";
    }

    /** 원 단위 천 단위 쉼표. 소수점 이하가 있으면 최대 2자리까지만 보인다(Locale.ROOT 기반 결정적 포맷). */
    public static String formatAmount(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(amount) + "원";
    }

    /**
     * 제어문자·서식문자(줄바꿈, 방향 제어 등)를 제거하고 연속 공백을 하나로 접은 뒤 길이를 제한한다.
     * 결과가 비면 null — 호출부는 ID 문구로 폴백한다.
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder(raw.length());
        boolean pendingSpace = false;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = cleaned.length() > 0;
                continue;
            }
            int type = Character.getType(cp);
            if (Character.isISOControl(cp) || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.PRIVATE_USE || type == Character.UNASSIGNED) {
                continue;
            }
            if (pendingSpace) {
                cleaned.append(' ');
                pendingSpace = false;
            }
            cleaned.appendCodePoint(cp);
        }
        if (cleaned.length() == 0) {
            return null;
        }
        String value = cleaned.toString();
        if (value.codePointCount(0, value.length()) <= MAX_LABEL_LENGTH) {
            return value;
        }
        int end = value.offsetByCodePoints(0, MAX_LABEL_LENGTH - 1);
        return value.substring(0, end).stripTrailing() + ELLIPSIS;
    }

    private static String sitePrefix(String siteName) {
        String name = sanitize(siteName);
        return name == null ? "" : "[" + name + "] ";
    }

    /** 사람이 알아볼 이름이 하나라도 있으면 이름만, 없으면 기존 "ID: n" 문구. */
    private static String detail(long id, String... labels) {
        List<String> parts = new ArrayList<>();
        for (String label : labels) {
            String value = sanitize(label);
            if (value != null) {
                parts.add(value);
            }
        }
        return parts.isEmpty() ? "ID: " + id : String.join(", ", parts);
    }
}
