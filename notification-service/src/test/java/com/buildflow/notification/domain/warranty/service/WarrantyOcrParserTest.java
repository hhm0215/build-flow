package com.buildflow.notification.domain.warranty.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class WarrantyOcrParserTest {

    @Test
    void 빈_텍스트는_모두_null() {
        WarrantyOcrParser.Result r = WarrantyOcrParser.parse("");
        assertThat(r.getInsuranceCompany()).isNull();
        assertThat(r.getPolicyNumber()).isNull();
        assertThat(r.getStartDate()).isNull();
        assertThat(r.getEndDate()).isNull();
        assertThat(r.extractedFieldCount()).isZero();
    }

    @Test
    void 알려진_보험사_추출() {
        String text = "본 증서는 서울보증보험 주식회사가 발행함";
        WarrantyOcrParser.Result r = WarrantyOcrParser.parse(text);
        assertThat(r.getInsuranceCompany()).isEqualTo("서울보증보험");
    }

    @Test
    void 증권번호_라벨_뒤_추출() {
        String text = "증권번호 : SG-2025-0001234";
        WarrantyOcrParser.Result r = WarrantyOcrParser.parse(text);
        assertThat(r.getPolicyNumber()).isEqualTo("SG-2025-0001234");
    }

    @Test
    void 보증기간_dash_포맷() {
        String text = "보증기간 2026-01-15 ~ 2027-01-14";
        WarrantyOcrParser.Result r = WarrantyOcrParser.parse(text);
        assertThat(r.getStartDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(r.getEndDate()).isEqualTo(LocalDate.of(2027, 1, 14));
    }

    @Test
    void 보증기간_한국어_포맷() {
        String text = "유효기간 2026년 3월 1일부터 2028년 2월 28일까지";
        WarrantyOcrParser.Result r = WarrantyOcrParser.parse(text);
        assertThat(r.getStartDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(r.getEndDate()).isEqualTo(LocalDate.of(2028, 2, 28));
    }

    @Test
    void 통합_시나리오_4필드_모두() {
        String text = """
                하자보증보험 증권
                보험회사: 한화손해보험 주식회사
                증권번호 : HW-2026-987654
                보장기간 2026.05.01 ~ 2027.04.30
                보증금액 50,000,000원
                """;
        WarrantyOcrParser.Result r = WarrantyOcrParser.parse(text);
        assertThat(r.getInsuranceCompany()).isEqualTo("한화손해보험");
        assertThat(r.getPolicyNumber()).isEqualTo("HW-2026-987654");
        assertThat(r.getStartDate()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(r.getEndDate()).isEqualTo(LocalDate.of(2027, 4, 30));
        assertThat(r.extractedFieldCount()).isEqualTo(4);
    }

    @Test
    void 단방향_기간만_있을_때_start만_추출_end는_null() {
        // 만료일이 누락된 PDF — 종전 PERIOD_PATTERN은 둘 다 null이었으나, 분리 패턴으로 start만 살림
        String text = "보증기간 2026.05.01 부터 효력 발생";
        WarrantyOcrParser.Result r = WarrantyOcrParser.parse(text);
        assertThat(r.getStartDate()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(r.getEndDate()).isNull();
    }

    @Test
    void 기간_라벨_없으면_둘다_null() {
        String text = "2026-01-01 어쩌고 저쩌고 2027-01-01";
        WarrantyOcrParser.Result r = WarrantyOcrParser.parse(text);
        assertThat(r.getStartDate()).isNull();
        assertThat(r.getEndDate()).isNull();
    }

    @Test
    void 라벨_두_번_등장_첫_라벨엔_날짜_없고_두번째에_둘_다() {
        // 첫 라벨 윈도우(200자) 안에 날짜가 없거나 부족할 때, 두 번째 라벨에서 두 날짜 모두 추출
        String text = "보증기간 안내사항: 약관에 따라 정해진 기간 동안 보증하며 자세한 내용은 첨부 약관 참고. " +
                "위 사항은 일반적인 안내로 실제 적용은 개별 증서에 따릅니다. " +
                "본 보증서의 보증기간 2026.05.01 ~ 2027.04.30 입니다.";
        WarrantyOcrParser.Result r = WarrantyOcrParser.parse(text);
        assertThat(r.getStartDate()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(r.getEndDate()).isEqualTo(LocalDate.of(2027, 4, 30));
    }
}
