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
}
