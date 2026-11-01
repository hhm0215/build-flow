package com.buildflow.notification.domain.notification.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationMessageFormatterTest {

    @Test
    void namesReplaceIdsWhenPresent() {
        assertThat(NotificationMessageFormatter.estimateConfirmed("강남 A현장", 1, "외벽 도장"))
                .isEqualTo("[강남 A현장] 견적서(외벽 도장)가 확정되었습니다.");
        assertThat(NotificationMessageFormatter.estimateSuperseded("강남 A현장", 1, "외벽 도장", 2))
                .isEqualTo("[강남 A현장] 견적서(외벽 도장)가 수정본(ID: 2) 확정으로 대체되었습니다.");
        assertThat(NotificationMessageFormatter.purchase("등록", "강남 A현장", 1, "시멘트", "대한건자재"))
                .isEqualTo("[강남 A현장] 매입(시멘트, 대한건자재)이 등록되었습니다.");
        assertThat(NotificationMessageFormatter.taxRegistered("강남 A현장", true, 1, "대한건설",
                new BigDecimal("1100000.00")))
                .isEqualTo("[강남 A현장] 매출 세금계산서(대한건설, 1,100,000원)가 등록되었습니다.");
        assertThat(NotificationMessageFormatter.taxPaymentConfirmed(null, 1, "대한건설", new BigDecimal("5000")))
                .isEqualTo("세금계산서(대한건설, 5,000원) 입금이 확인되었습니다.");
        assertThat(NotificationMessageFormatter.warrantyExpiring("강남 A현장", 5, "보험사", 18))
                .isEqualTo("[강남 A현장] 하자보증보험(ID: 5, 보험사) 만료까지 18일 남았습니다.");
    }

    @Test
    void missingOrBlankFieldsFallBackToLegacyIdMessages() {
        assertThat(NotificationMessageFormatter.estimateConfirmed(null, 1, null))
                .isEqualTo("견적서(ID: 1)가 확정되었습니다.");
        assertThat(NotificationMessageFormatter.estimateDeleted("  ", 1, "\n\t"))
                .isEqualTo("견적서(ID: 1)가 삭제되었습니다.");
        assertThat(NotificationMessageFormatter.purchase("삭제", null, 2, null, null))
                .isEqualTo("매입(ID: 2)이 삭제되었습니다.");
        assertThat(NotificationMessageFormatter.purchase("수정", null, 2, null, "대한건자재"))
                .isEqualTo("매입(대한건자재)이 수정되었습니다.");
        assertThat(NotificationMessageFormatter.taxRegistered(null, false, 3, null, null))
                .isEqualTo("매입 세금계산서(ID: 3)가 등록되었습니다.");
        assertThat(NotificationMessageFormatter.taxPaymentConfirmed(null, 3, null, null))
                .isEqualTo("세금계산서(ID: 3) 입금이 확인되었습니다.");
        assertThat(NotificationMessageFormatter.warrantyExpiring(null, 5, "보험사", 18))
                .isEqualTo("하자보증보험(ID: 5, 보험사) 만료까지 18일 남았습니다.");
    }

    @Test
    void longNamesAreTruncatedWithEllipsis() {
        String longName = "가".repeat(80);
        String sanitized = NotificationMessageFormatter.sanitize(longName);
        assertThat(sanitized).hasSize(NotificationMessageFormatter.MAX_LABEL_LENGTH).endsWith("…");
        assertThat(sanitized).isEqualTo("가".repeat(49) + "…");
        assertThat(NotificationMessageFormatter.sanitize("가".repeat(50))).isEqualTo("가".repeat(50));
        // 보충 문자(이모지)를 반으로 자르지 않는다
        String emoji = "🏗".repeat(60);
        String cut = NotificationMessageFormatter.sanitize(emoji);
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(50);
        assertThat(cut).isEqualTo("🏗".repeat(49) + "…");
    }

    @Test
    void controlAndFormatCharactersAreRemoved() {
        assertThat(NotificationMessageFormatter.sanitize("  대한\r\n건설\u0000 ‮(주)​  "))
                .isEqualTo("대한 건설 (주)");
        assertThat(NotificationMessageFormatter.sanitize("\u0007\u001B")).isNull();
        assertThat(NotificationMessageFormatter.sanitize(null)).isNull();
    }

    @Test
    void amountsUseDeterministicThousandsSeparators() {
        assertThat(NotificationMessageFormatter.formatAmount(new BigDecimal("0"))).isEqualTo("0원");
        assertThat(NotificationMessageFormatter.formatAmount(new BigDecimal("999"))).isEqualTo("999원");
        assertThat(NotificationMessageFormatter.formatAmount(new BigDecimal("1000.00"))).isEqualTo("1,000원");
        assertThat(NotificationMessageFormatter.formatAmount(new BigDecimal("1234567.50"))).isEqualTo("1,234,567.5원");
        assertThat(NotificationMessageFormatter.formatAmount(new BigDecimal("9999999999999.99")))
                .isEqualTo("9,999,999,999,999.99원");
        assertThat(NotificationMessageFormatter.formatAmount(null)).isNull();
    }
}
