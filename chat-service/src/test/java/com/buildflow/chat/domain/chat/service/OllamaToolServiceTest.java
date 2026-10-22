package com.buildflow.chat.domain.chat.service;

import com.buildflow.chat.domain.chat.tool.ToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void toolResultMessage_호출_id와_도구명을_결과에_연결한다() throws Exception {
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(eq("getSiteProfit"), any())).thenReturn("{\"margin\":1}");
        OllamaToolService service = new OllamaToolService(mock(WebClient.class), new ObjectMapper(), executor);
        List<String> statuses = new ArrayList<>();

        Map<String, Object> result = service.toolResultMessage(new ObjectMapper().readTree(
                "{\"id\":\"call_7\",\"function\":{\"name\":\"getSiteProfit\",\"arguments\":\"{\\\"siteId\\\":3}\"}}"),
                statuses::add);

        assertThat(result).containsEntry("role", "tool")
                .containsEntry("tool_call_id", "call_7")
                .containsEntry("tool_name", "getSiteProfit")
                .containsEntry("content", "{\"margin\":1}");
        assertThat(statuses).containsExactly("tool");
        verify(executor).execute(eq("getSiteProfit"), eq(new ObjectMapper().readTree("{\"siteId\":3}")));
    }

    @Test
    void toolResultMessage_id가_없으면_tool_call_id를_넣지_않는다() throws Exception {
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(eq("listSites"), any())).thenReturn("[]");
        OllamaToolService service = new OllamaToolService(mock(WebClient.class), new ObjectMapper(), executor);

        Map<String, Object> result = service.toolResultMessage(new ObjectMapper().readTree(
                "{\"function\":{\"name\":\"listSites\",\"arguments\":{}}}"), status -> { });

        assertThat(result).doesNotContainKey("tool_call_id").containsEntry("tool_name", "listSites");
    }

    @Test
    void toolResultMessage_이름_없는_호출도_오류_결과로_짝을_맞춘다() throws Exception {
        ToolExecutor executor = mock(ToolExecutor.class);
        OllamaToolService service = new OllamaToolService(mock(WebClient.class), new ObjectMapper(), executor);
        List<String> statuses = new ArrayList<>();

        Map<String, Object> result = service.toolResultMessage(new ObjectMapper().readTree(
                "{\"id\":\"call_9\",\"function\":{\"arguments\":{}}}"), statuses::add);

        assertThat(result).containsEntry("tool_call_id", "call_9").doesNotContainKey("tool_name");
        assertThat((String) result.get("content")).contains("error");
        assertThat(statuses).isEmpty();
        verify(executor, never()).execute(any(), any());
    }

    @Test
    void 도구를_고르지_않은_데이터_질문은_규칙_폴백_결과로_다시_답한다() {
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(eq("listSites"), any())).thenReturn("[{\"id\":3,\"siteName\":\"부산 창고\"}]");
        when(executor.execute(eq("getSiteProfit"), any())).thenReturn("{\"margin\":500}");
        List<String> bodies = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    String answer = calls.getAndIncrement() == 0 ? "마진은 아마 1억 원입니다." : "부산 창고 마진은 500원입니다.";
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body("{\"message\":{\"role\":\"assistant\",\"content\":\"" + answer + "\"}}")
                            .build());
                })
                .build();
        OllamaToolService service = new OllamaToolService(client, new ObjectMapper(), executor);
        ReflectionTestUtils.setField(service, "model", "qwen2.5:7b");
        ReflectionTestUtils.setField(service, "timeoutSeconds", 5);
        ReflectionTestUtils.setField(service, "maxToolRounds", 4);
        List<Object> messages = new ArrayList<>(List.of(Map.of("role", "user", "content", "부산 창고 마진 얼마야?")));

        String answer = service.run(messages);

        assertThat(answer).isEqualTo("부산 창고 마진은 500원입니다.");
        assertThat(calls).hasValue(2);
        assertThat(messages).anySatisfy(m -> assertThat(m).isEqualTo(Map.of(
                "role", "tool", "tool_name", "getSiteProfit", "content", "{\"margin\":500}")));
    }

    @Test
    void 업무와_무관한_질문은_폴백_없이_첫_답변을_쓴다() {
        ToolExecutor executor = mock(ToolExecutor.class);
        AtomicInteger calls = new AtomicInteger();
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    calls.incrementAndGet();
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body("{\"message\":{\"role\":\"assistant\",\"content\":\"안녕하세요\"}}")
                            .build());
                })
                .build();
        OllamaToolService service = new OllamaToolService(client, new ObjectMapper(), executor);
        ReflectionTestUtils.setField(service, "model", "qwen2.5:7b");
        ReflectionTestUtils.setField(service, "timeoutSeconds", 5);
        ReflectionTestUtils.setField(service, "maxToolRounds", 4);

        String answer = service.run(new ArrayList<>(List.of(Map.of("role", "user", "content", "안녕"))));

        assertThat(answer).isEqualTo("안녕하세요");
        assertThat(calls).hasValue(1);
        verify(executor, never()).execute(any(), any());
    }
}
