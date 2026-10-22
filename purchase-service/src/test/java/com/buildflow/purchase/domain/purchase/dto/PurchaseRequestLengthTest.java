package com.buildflow.purchase.domain.purchase.dto;

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
class PurchaseRequestLengthTest {

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

    private static final Map<String, Object> BASE = Map.of("siteId", 1, "itemName", "철근", "quantity", 1, "unitPrice", 1000);

    @Test
    void 매입_메모는_TEXT_바이트_한도_아래로_제한된다() throws Exception {
        for (Class<?> type : new Class<?>[]{PurchaseCreateRequest.class, PurchaseUpdateRequest.class}) {
            assertLimit(type, BASE, "memo", 16000, "메모는 16000자 이하여야 합니다.");
        }
    }
}
