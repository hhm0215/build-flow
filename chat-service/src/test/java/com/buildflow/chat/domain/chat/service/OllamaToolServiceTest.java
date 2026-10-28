package com.buildflow.chat.domain.chat.service;

import com.buildflow.chat.domain.chat.tool.IntentRouter;
import com.buildflow.chat.domain.chat.tool.ToolExecutor;
import com.buildflow.chat.global.exception.BusinessException;
import com.buildflow.chat.global.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    private static final String FIRST_NO_TOOL = "{\"message\":{\"role\":\"assistant\",\"content\":\"마진은 아마 1억 원입니다.\"}}";

    /** 호출 순서대로 응답을 돌려준다. 문자열 배열 하나 = 응답 하나, 원소 하나 = 네트워크 청크 하나(UTF-8 바이트 임의 분할 가능). */
    private OllamaToolService streamingService(ToolExecutor executor, int maxRounds,
                                               byte[][]... responses) {
        AtomicInteger calls = new AtomicInteger();
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    byte[][] chunks = responses[calls.getAndIncrement()];
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/x-ndjson")
                            .body(Flux.fromArray(chunks).map(DefaultDataBufferFactory.sharedInstance::wrap))
                            .build());
                })
                .build();
        OllamaToolService service = new OllamaToolService(client, new ObjectMapper(), executor);
        ReflectionTestUtils.setField(service, "model", "qwen2.5:7b");
        ReflectionTestUtils.setField(service, "timeoutSeconds", 5);
        ReflectionTestUtils.setField(service, "maxToolRounds", maxRounds);
        return service;
    }

    private static byte[][] whole(String body) {
        return new byte[][]{body.getBytes(StandardCharsets.UTF_8)};
    }

    /** 바이트 위치 기준으로 잘라 줄·한글 문자 중간이 청크 경계에 걸리게 한다. */
    private static byte[][] splitEvery(String body, int size) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        List<byte[]> chunks = new ArrayList<>();
        for (int i = 0; i < bytes.length; i += size) {
            chunks.add(Arrays.copyOfRange(bytes, i, Math.min(bytes.length, i + size)));
        }
        return chunks.toArray(new byte[0][]);
    }

    private static String ndjsonLine(String content, boolean done) {
        return "{\"model\":\"qwen2.5:7b\",\"message\":{\"role\":\"assistant\",\"content\":\"" + content
                + "\"},\"done\":" + done + "}\n";
    }

    private ToolExecutor fallbackExecutor() {
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(eq("listSites"), any())).thenReturn("[{\"id\":3,\"siteName\":\"부산 창고\"}]");
        when(executor.execute(eq("getSiteProfit"), any())).thenReturn("{\"margin\":500}");
        return executor;
    }

    @Test
    void 폴백_재답변은_NDJSON_조각을_도착하는_대로_흘리고_전체를_반환한다() {
        String ndjson = ndjsonLine("부산 창고", false) + ndjsonLine(" 마진은", false)
                + ndjsonLine(" 500원입니다.", false) + ndjsonLine("", true);
        // 7바이트 단위 분할 — 줄 경계와 한글(3바이트) 중간이 청크 경계에 걸린다
        OllamaToolService service = streamingService(fallbackExecutor(), 4,
                whole(FIRST_NO_TOOL), splitEvery(ndjson, 7));
        List<String> statuses = new ArrayList<>();
        List<String> tokens = new ArrayList<>();

        String answer = service.runStreaming(new ArrayList<>(List.of(Map.of("role", "user", "content", "부산 창고 마진 얼마야?"))),
                statuses::add, tokens::add);

        assertThat(tokens).containsExactly("부산 창고", " 마진은", " 500원입니다.");
        assertThat(answer).isEqualTo("부산 창고 마진은 500원입니다.").isEqualTo(String.join("", tokens));
        assertThat(statuses).endsWith("answering");
    }

    @Test
    void 마지막_줄에_개행이_없어도_처리하고_동기_run은_같은_결과다() {
        String ndjson = ndjsonLine("부산", false) + ndjsonLine(" 창고 마진 500원", false).trim();
        OllamaToolService service = streamingService(fallbackExecutor(), 4,
                whole(FIRST_NO_TOOL), splitEvery(ndjson, 5));

        String answer = service.run(new ArrayList<>(List.of(Map.of("role", "user", "content", "부산 창고 마진 얼마야?"))));

        assertThat(answer).isEqualTo("부산 창고 마진 500원");
    }

    @Test
    void 라운드_상한_후_강제_답변도_스트리밍한다() {
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn("[]");
        String toolCall = "{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":"
                + "[{\"function\":{\"name\":\"listSites\",\"arguments\":{}}}]}}";
        String ndjson = ndjsonLine("현장이", false) + ndjsonLine(" 없습니다.", false) + ndjsonLine("", true);
        OllamaToolService service = streamingService(executor, 1, whole(toolCall), whole(ndjson));
        List<String> tokens = new ArrayList<>();

        String answer = service.runStreaming(new ArrayList<>(List.of(Map.of("role", "user", "content", "현장 목록"))),
                status -> { }, tokens::add);

        assertThat(tokens).containsExactly("현장이", " 없습니다.");
        assertThat(answer).isEqualTo("현장이 없습니다.");
    }

    @Test
    void 스트리밍_답변이_비면_안내_문구를_흘린다() {
        String ndjson = ndjsonLine("", false) + ndjsonLine("  ", false) + ndjsonLine("", true);
        OllamaToolService service = streamingService(fallbackExecutor(), 4,
                whole(FIRST_NO_TOOL), whole(ndjson));
        List<String> tokens = new ArrayList<>();

        String answer = service.runStreaming(new ArrayList<>(List.of(Map.of("role", "user", "content", "부산 창고 마진 얼마야?"))),
                status -> { }, tokens::add);

        assertThat(answer).startsWith("죄송합니다.");
        assertThat(String.join("", tokens)).isEqualTo(answer);
    }

    @Test
    void 스트리밍_오류_줄은_CHAT_LLM_FAILED로_바꾼다() {
        String ndjson = ndjsonLine("부산", false) + "{\"error\":\"model crashed\"}\n";
        OllamaToolService service = streamingService(fallbackExecutor(), 4,
                whole(FIRST_NO_TOOL), whole(ndjson));

        assertThatThrownBy(() -> service.run(new ArrayList<>(List.of(Map.of("role", "user", "content", "부산 창고 마진 얼마야?")))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_LLM_FAILED);
    }

    @Test
    void 조각_소비자_예외는_변환하지_않고_그대로_전파한다() {
        String ndjson = ndjsonLine("부산", false) + ndjsonLine(" 창고", false);
        OllamaToolService service = streamingService(fallbackExecutor(), 4,
                whole(FIRST_NO_TOOL), whole(ndjson));
        IllegalStateException disconnected = new IllegalStateException("client gone");

        assertThatThrownBy(() -> service.runStreaming(
                new ArrayList<>(List.of(Map.of("role", "user", "content", "부산 창고 마진 얼마야?"))),
                status -> { }, token -> { throw disconnected; }))
                .isSameAs(disconnected);
    }

    @Test
    void splitLines는_개행에서만_자르고_잔여분을_남긴다() {
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        List<String> lines = new ArrayList<>();
        byte[] bytes = "가나\n다".getBytes(StandardCharsets.UTF_8);

        OllamaToolService.splitLines(Arrays.copyOfRange(bytes, 0, 2), pending, lines);
        assertThat(lines).isEmpty();
        OllamaToolService.splitLines(Arrays.copyOfRange(bytes, 2, bytes.length), pending, lines);

        assertThat(lines).containsExactly("가나");
        assertThat(pending.toString(StandardCharsets.UTF_8)).isEqualTo("다");
    }

    @Test
    void 현장_이름이_든_질문은_siteId_힌트를_시스템_메시지로_넣는다() {
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(eq("listSites"), any()))
                .thenReturn("[{\"id\":15,\"siteName\":\"[QA] 1022-144647 현장\"},{\"id\":4,\"siteName\":\"부산 창고\"}]");
        OllamaToolService service = new OllamaToolService(WebClient.builder().build(), new ObjectMapper(), executor);
        List<Object> messages = new ArrayList<>(List.of(Map.of("role", "user", "content", "[QA] 1022-144647 현장 손익 알려줘")));

        List<IntentRouter.SiteRef> sites = service.addSiteHint(messages);

        assertThat(sites).hasSize(2);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1)).isEqualTo(Map.of("role", "system",
                "content", "질문에 언급된 현장 데이터: [{\"siteId\":15,\"siteName\":\"[QA] 1022-144647 현장\"}] 현장별 도구는 이 siteId로 호출한다."));
    }

    @Test
    void 현장_도구가_필요_없는_질문은_목록을_조회하지_않는다() {
        ToolExecutor executor = mock(ToolExecutor.class);
        OllamaToolService service = new OllamaToolService(WebClient.builder().build(), new ObjectMapper(), executor);
        List<Object> messages = new ArrayList<>(List.of(Map.of("role", "user", "content", "안녕")));

        assertThat(service.addSiteHint(messages)).isNull();
        assertThat(messages).hasSize(1);
        verify(executor, never()).execute(any(), any());
    }

    @Test
    void 힌트_현장_이름은_제어문자를_지우고_길이를_제한한다() {
        assertThat(OllamaToolService.hintName("가\n나\t다")).isEqualTo("가 나 다");
        assertThat(OllamaToolService.hintName("가".repeat(80))).hasSize(OllamaToolService.HINT_NAME_MAX);
    }

    @Test
    void 이름_매칭이_없으면_힌트_없이_목록만_돌려준다() {
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(eq("listSites"), any())).thenReturn("[{\"id\":4,\"siteName\":\"부산 창고\"}]");
        OllamaToolService service = new OllamaToolService(WebClient.builder().build(), new ObjectMapper(), executor);
        List<Object> messages = new ArrayList<>(List.of(Map.of("role", "user", "content", "서울 현장 마진 얼마야?")));

        assertThat(service.addSiteHint(messages)).hasSize(1);
        assertThat(messages).hasSize(1);
    }

    @Test
    void 힌트와_폴백은_현장_목록을_한_번만_조회하고_첫_상태를_먼저_보낸다() {
        ToolExecutor executor = fallbackExecutor();
        AtomicInteger calls = new AtomicInteger();
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    String answer = calls.getAndIncrement() == 0 ? "마진은 아마 1억 원입니다." : "부산 창고 마진은 500원입니다.";
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body("{\"message\":{\"role\":\"assistant\",\"content\":\"" + answer + "\"},\"done\":true}")
                            .build());
                })
                .build();
        OllamaToolService service = new OllamaToolService(client, new ObjectMapper(), executor);
        ReflectionTestUtils.setField(service, "model", "qwen2.5:7b");
        ReflectionTestUtils.setField(service, "timeoutSeconds", 5);
        ReflectionTestUtils.setField(service, "maxToolRounds", 4);
        List<String> statuses = new ArrayList<>();

        service.runStreaming(new ArrayList<>(List.of(Map.of("role", "user", "content", "부산 창고 마진 얼마야?"))),
                statuses::add, token -> { });

        verify(executor, times(1)).execute(eq("listSites"), any());
        verify(executor, times(1)).execute(eq("getSiteProfit"), any());
        assertThat(statuses.get(0)).isEqualTo("thinking");
        assertThat(statuses.stream().filter("thinking"::equals).count()).isEqualTo(1);
    }
}
