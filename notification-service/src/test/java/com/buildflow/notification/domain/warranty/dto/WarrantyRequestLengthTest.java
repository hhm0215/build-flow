package com.buildflow.notification.domain.warranty.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 입력 문자열이 DB 컬럼 한도를 넘으면 저장 전에 400(검증 실패)으로 막히는지 고정한다. */
class WarrantyRequestLengthTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    private <T> Set<String> messages(Class<T> type, Map<String, Object> base, String field, Object value) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(base);
        body.put(field, value);
        T request = mapper.readValue(mapper.writeValueAsString(body), type);
        return VALIDATOR.validate(request).stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }

    private <T> void assertLimit(Class<T> type, Map<String, Object> base, String field, int max, String message) throws Exception {
        assertThat(messages(type, base, field, "가".repeat(max))).as(field + " 경계값 허용").isEmpty();
        assertThat(messages(type, base, field, "가".repeat(max + 1))).as(field + " 초과 거부").containsExactly(message);
    }

    private static final Map<String, Object> BASE = Map.of("siteId", 1, "insuranceCompany", "서울보증보험",
            "startDate", "2026-10-22", "endDate", "2028-10-21");

    @Test
    void 보증_등록과_수정의_문자열은_컬럼_한도로_제한된다() throws Exception {
        for (Class<?> type : new Class<?>[]{WarrantyCreateRequest.class, WarrantyUpdateRequest.class}) {
            assertLimit(type, BASE, "insuranceCompany", 200, "보험사는 200자 이하여야 합니다.");
            assertLimit(type, BASE, "policyNumber", 100, "증권번호는 100자 이하여야 합니다.");
            assertLimit(type, BASE, "memo", 16000, "메모는 16000자 이하여야 합니다.");
        }
    }

    @Test
    void 수정의_명시적_null은_비우기로_허용된다() throws Exception {
        assertThat(messages(WarrantyUpdateRequest.class, BASE, "memo", null)).isEmpty();
        assertThat(messages(WarrantyUpdateRequest.class, BASE, "policyNumber", null)).isEmpty();
    }
}
