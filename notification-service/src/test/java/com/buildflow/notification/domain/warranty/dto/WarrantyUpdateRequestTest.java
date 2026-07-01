package com.buildflow.notification.domain.warranty.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PATCH 3-state 시맨틱을 lock-in — DTO 구조가 흐트러지면 실무의 clear/skip/update 계약이 깨지므로
 * ObjectMapper 역직렬화 결과로 회귀 검출한다.
 */
class WarrantyUpdateRequestTest {

    // findAndRegisterModules로 Jdk8Module + JavaTimeModule을 등록해야 Optional/LocalDate가 정상 매핑됨.
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void 필드_없음_은_null_로_skip_시맨틱() throws Exception {
        String json = "{\"insuranceCompany\":\"삼성화재\",\"startDate\":\"2026-01-01\",\"endDate\":\"2027-01-01\"}";
        WarrantyUpdateRequest r = mapper.readValue(json, WarrantyUpdateRequest.class);
        assertThat(r.getMemo()).isNull();
        assertThat(r.getPolicyNumber()).isNull();
        assertThat(r.getCoverageAmount()).isNull();
    }

    @Test
    void JSON_null_은_Optional_empty_로_clear_시맨틱() throws Exception {
        String json = "{\"insuranceCompany\":\"삼성화재\",\"startDate\":\"2026-01-01\",\"endDate\":\"2027-01-01\","
                + "\"memo\":null,\"policyNumber\":null,\"coverageAmount\":null}";
        WarrantyUpdateRequest r = mapper.readValue(json, WarrantyUpdateRequest.class);
        assertThat(r.getMemo()).isNotNull().isEmpty();
        assertThat(r.getPolicyNumber()).isNotNull().isEmpty();
        assertThat(r.getCoverageAmount()).isNotNull().isEmpty();
    }

    @Test
    void JSON_값_있음_은_Optional_of_로_update_시맨틱() throws Exception {
        String json = "{\"insuranceCompany\":\"삼성화재\",\"startDate\":\"2026-01-01\",\"endDate\":\"2027-01-01\","
                + "\"memo\":\"현장 점검 완료\",\"policyNumber\":\"SS-2026-01\",\"coverageAmount\":10000000}";
        WarrantyUpdateRequest r = mapper.readValue(json, WarrantyUpdateRequest.class);
        assertThat(r.getMemo()).contains("현장 점검 완료");
        assertThat(r.getPolicyNumber()).contains("SS-2026-01");
        assertThat(r.getCoverageAmount()).contains(10_000_000L);
    }
}
