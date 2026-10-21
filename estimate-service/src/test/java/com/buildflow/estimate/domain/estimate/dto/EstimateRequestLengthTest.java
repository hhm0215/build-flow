package com.buildflow.estimate.domain.estimate.dto;

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
class EstimateRequestLengthTest {

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

    private static Map<String, Object> item() {
        return new LinkedHashMap<>(Map.of("itemName", "철근", "unit", "톤", "quantity", 1, "unitPrice", 1000));
    }

    private static Map<String, Object> estimate() {
        return Map.of("siteId", 1, "title", "견적", "estimateDate", "2026-10-22", "items", java.util.List.of(item()));
    }

    @Test
    void 견적_생성_수정의_제목과_메모는_컬럼_한도로_제한된다() throws Exception {
        for (Class<?> type : new Class<?>[]{EstimateCreateRequest.class, EstimateUpdateRequest.class}) {
            assertLimit(type, estimate(), "title", 200, "견적서명은 200자 이하여야 합니다.");
            assertLimit(type, estimate(), "memo", 1000, "메모는 1000자 이하여야 합니다.");
        }
    }

    @Test
    void 견적_항목명과_단위는_컬럼_한도로_제한되고_목록_안에서도_검증된다() throws Exception {
        assertLimit(EstimateItemRequest.class, item(), "itemName", 200, "항목명은 200자 이하여야 합니다.");
        assertLimit(EstimateItemRequest.class, item(), "unit", 20, "단위는 20자 이하여야 합니다.");
        Map<String, Object> longUnit = item();
        longUnit.put("unit", "가".repeat(21));
        assertThat(messages(EstimateCreateRequest.class, estimate(), "items", java.util.List.of(longUnit)))
                .containsExactly("단위는 20자 이하여야 합니다.");
    }
}
