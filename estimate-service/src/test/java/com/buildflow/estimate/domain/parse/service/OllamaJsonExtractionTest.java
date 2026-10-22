package com.buildflow.estimate.domain.parse.service;

import com.buildflow.estimate.domain.parse.dto.ParsedItemResult;
import com.buildflow.estimate.global.exception.BusinessException;
import com.buildflow.estimate.global.exception.ErrorCode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OllamaJsonExtractionTest {

    // Spring Boot 기본 ObjectMapper처럼 모르는 필드는 무시한다
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private OllamaService serviceReplying(Object content) throws Exception {
        String body = mapper.writeValueAsString(Map.of("message", content == null ? Map.of() : Map.of("content", content)));
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(body)
                        .build()))
                .build();
        OllamaService service = new OllamaService(client, mapper);
        ReflectionTestUtils.setField(service, "model", "synthetic-model");
        ReflectionTestUtils.setField(service, "timeoutSeconds", 5);
        return service;
    }

    private static final String ITEMS =
            "[{\"itemName\":\"합성 철근 가공\",\"unit\":\"톤\",\"quantity\":2.5,\"unitPrice\":1000,\"amount\":2500}]";

    @Test
    void 순수_JSON_배열을_읽는다() throws Exception {
        List<ParsedItemResult> items = serviceReplying(ITEMS).parseItems("synthetic");

        assertEquals(1, items.size());
        assertEquals("합성 철근 가공", items.get(0).getItemName());
        assertEquals(0, new BigDecimal("2.5").compareTo(items.get(0).getQuantity()));
    }

    @Test
    void 코드블록과_설명이_섞여도_배열만_추출한다() throws Exception {
        String content = "결과입니다.\n```json\n" + ITEMS + "\n```\n끝.";

        assertEquals(1, serviceReplying(content).parseItems("synthetic").size());
    }

    @Test
    void 배열_앞_설명에_대괄호가_있어도_실제_배열을_찾는다() throws Exception {
        String content = "[참고] 소계 행은 제외했습니다.\n" + ITEMS;

        List<ParsedItemResult> items = serviceReplying(content).parseItems("synthetic");

        assertEquals(1, items.size());
        assertEquals("톤", items.get(0).getUnit());
    }

    @Test
    void 배열이_없거나_깨지면_파싱_실패로_응답한다() throws Exception {
        assertEquals(ErrorCode.OLLAMA_PARSE_FAILED, assertThrows(BusinessException.class,
                () -> serviceReplying("항목을 찾지 못했습니다.").parseItems("synthetic")).getErrorCode());
        assertEquals(ErrorCode.OLLAMA_PARSE_FAILED, assertThrows(BusinessException.class,
                () -> serviceReplying("[{\"itemName\":").parseItems("synthetic")).getErrorCode());
    }

    @Test
    void 응답_본문이_비어도_500이_아니라_파싱_실패다() throws Exception {
        assertEquals(ErrorCode.OLLAMA_PARSE_FAILED, assertThrows(BusinessException.class,
                () -> serviceReplying(null).parseItems("synthetic")).getErrorCode());
    }
}
