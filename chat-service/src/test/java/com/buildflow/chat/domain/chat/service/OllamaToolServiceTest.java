package com.buildflow.chat.domain.chat.service;

import com.buildflow.chat.domain.chat.tool.ToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OllamaToolServiceTest {

    @Test
    void availabilityRequiresConfiguredModel() {
        OllamaToolService service = serviceWithTags("{\"models\":[{\"name\":\"qwen2.5:7b\"}]}");
        assertThat(service.isAvailable()).isTrue();

        service = serviceWithTags("{\"models\":[{\"name\":\"another:latest\"}]}");
        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    void availabilityReturnsFalseWhenModelServerFails() {
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new IllegalStateException("connection refused")))
                .build();
        OllamaToolService service = new OllamaToolService(client, new ObjectMapper(), mock(ToolExecutor.class));
        ReflectionTestUtils.setField(service, "model", "qwen2.5:7b");

        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    void availabilityCachesShortLivedProbe() {
        AtomicInteger calls = new AtomicInteger();
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    calls.incrementAndGet();
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body("{\"models\":[{\"name\":\"qwen2.5:7b\"}]}")
                            .build());
                })
                .build();
        OllamaToolService service = new OllamaToolService(client, new ObjectMapper(), mock(ToolExecutor.class));
        ReflectionTestUtils.setField(service, "model", "qwen2.5:7b");

        assertThat(service.isAvailable()).isTrue();
        assertThat(service.isAvailable()).isTrue();
        assertThat(calls).hasValue(1);
    }

    private OllamaToolService serviceWithTags(String body) {
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .build()))
                .build();
        OllamaToolService service = new OllamaToolService(client, new ObjectMapper(), mock(ToolExecutor.class));
        ReflectionTestUtils.setField(service, "model", "qwen2.5:7b");
        return service;
    }

    @Test
    void chunkAnswer_조각을_모두_이으면_원문과_같다() {
        String answer = "강남 리모델링 현장의 마진은 1,000,000원이고 마진율은 12.5%입니다. 자세한 내역은 견적서를 확인하세요.";

        List<String> chunks = OllamaToolService.chunkAnswer(answer);

        assertThat(String.join("", chunks)).isEqualTo(answer);
        assertThat(chunks.size()).isGreaterThan(1);
    }

    @Test
    void chunkAnswer_공백_경계에서만_자른다() {
        String answer = "마진율은 12.5%입니다";

        List<String> chunks = OllamaToolService.chunkAnswer(answer);

        // 단어(비공백 연속) 중간에서 잘리지 않는다 — 각 조각은 비공백 문자로 끝난다
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(Character.isWhitespace(chunk.charAt(chunk.length() - 1))).isFalse());
        assertThat(String.join("", chunks)).isEqualTo(answer);
    }

    @Test
    void chunkAnswer_짧은_답변과_빈_답변() {
        assertThat(OllamaToolService.chunkAnswer("짧다")).containsExactly("짧다");
        assertThat(OllamaToolService.chunkAnswer("")).isEmpty();
    }
}
