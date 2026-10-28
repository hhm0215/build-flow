package com.buildflow.chat.domain.chat.service;

import com.buildflow.chat.domain.chat.tool.IntentRouter;
import com.buildflow.chat.domain.chat.tool.ToolCatalog;
import com.buildflow.chat.domain.chat.tool.ToolExecutor;
import com.buildflow.chat.global.exception.BusinessException;
import com.buildflow.chat.global.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Ollama 툴-콜링 에이전트 루프.
 * LLM이 도구를 고르면 {@link ToolExecutor}로 실행해 결과를 다시 넣고, 도구 호출이 없을 때까지
 * (또는 max 라운드까지) 반복한 뒤 최종 한국어 답변을 반환한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaToolService {

    private static final int CHUNK_MIN_LENGTH = 16;
    private static final long AVAILABILITY_CACHE_NANOS = TimeUnit.SECONDS.toNanos(5);

    private long availabilityCheckedAtNanos;
    private boolean cachedAvailability;

    private final WebClient ollamaWebClient;
    private final ObjectMapper objectMapper;
    private final ToolExecutor toolExecutor;

    @Value("${ollama.api.model}")
    private String model;

    @Value("${ollama.api.timeout}")
    private int timeoutSeconds;

    /** 힌트에 넣는 현장 이름 최대 길이. */
    static final int HINT_NAME_MAX = 50;

    @Value("${chat.max-tool-rounds:4}")
    private int maxToolRounds;

    /** 질문을 받기 전에 모델 서버와 설정된 모델이 모두 준비됐는지 짧게 확인한다. */
    public synchronized boolean isAvailable() {
        long now = System.nanoTime();
        if (availabilityCheckedAtNanos != 0 && now - availabilityCheckedAtNanos < AVAILABILITY_CACHE_NANOS) {
            return cachedAvailability;
        }
        cachedAvailability = probeAvailability();
        availabilityCheckedAtNanos = System.nanoTime();
        return cachedAvailability;
    }

    private boolean probeAvailability() {
        try {
            JsonNode response = ollamaWebClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(2))
                    .block();
            JsonNode models = response == null ? null : response.get("models");
            if (models == null || !models.isArray()) {
                return false;
            }
            for (JsonNode entry : models) {
                if (model.equals(entry.path("name").asText())) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("AI 모델 준비 상태 확인 실패: {}", e.getClass().getSimpleName());
        }
        return false;
    }

    /** messages: system/history/user가 채워진 가변 리스트. 루프 중 도구 왕복이 추가된다. */
    public String run(List<Object> messages) {
        return runStreaming(messages, status -> { }, token -> { });
    }

    /**
     * run()과 동일한 에이전트 루프에 진행 상태·답변 조각 콜백을 얹은 버전.
     * 도구 선택 라운드(tools 포함)는 도구 호출 판정을 위해 stream:false로 받는다. 그 라운드가 도구 없이
     * 바로 답했으면 이미 생성된 답변을 그대로 조각내 전달한다(재생성 없음, LLM 1회).
     * 폴백 후 재답변·라운드 상한 후 강제 답변처럼 도구 없이 새로 생성하는 답변은 stream:true로
     * 모델 토큰이 도착하는 대로 전달한다. 어느 경우든 전달한 조각을 이으면 반환값과 같다.
     */
    public String runStreaming(List<Object> messages, Consumer<String> statusConsumer,
                               Consumer<String> tokenConsumer) {
        // 현장 힌트 조회(Feign)가 느려도 첫 상태가 바로 보이도록 먼저 보낸다.
        statusConsumer.accept("thinking");
        List<IntentRouter.SiteRef> sites = addSiteHint(messages);
        for (int round = 0; round < maxToolRounds; round++) {
            if (round > 0) {
                statusConsumer.accept("thinking");
            }
            JsonNode message = callOllama(messages, ToolCatalog.tools());
            JsonNode toolCalls = message.get("tool_calls");

            if (toolCalls == null || !toolCalls.isArray() || toolCalls.isEmpty()) {
                if (round == 0 && applyFallback(messages, sites, statusConsumer)) {
                    // 데이터 질문인데 도구 없이 답하려 했다 → 규칙으로 고른 도구 결과를 넣고 다시 답하게 한다.
                    return streamAnswer(messages, statusConsumer, tokenConsumer);
                }
                return emitAnswer(textOrFallback(message), statusConsumer, tokenConsumer);
            }

            // 어시스턴트의 도구 호출 메시지를 컨텍스트에 그대로 되돌려 넣는다.
            messages.add(objectMapper.convertValue(message, Map.class));

            for (JsonNode call : toolCalls) {
                messages.add(toolResultMessage(call, statusConsumer));
            }
        }

        // 도구 라운드 상한 초과 → 도구 없이 최종 답변을 강제한다.
        return streamAnswer(messages, statusConsumer, tokenConsumer);
    }

    /**
     * 도구 없이 최종 답변만 생성하는 호출 — Ollama stream:true(NDJSON)로 받아 조각이 도착하는 대로
     * tokenConsumer에 넘기고 전체 연결 문자열을 반환한다. 공백만 온 앞부분은 실제 글자가 올 때까지 모아
     * 빈 답변이면 안내 문구 하나만 흘린다(조각 연결 = 반환값 유지).
     */
    private String streamAnswer(List<Object> messages, Consumer<String> statusConsumer,
                                Consumer<String> tokenConsumer) {
        StringBuilder full = new StringBuilder();
        boolean[] started = {false};
        streamOllama(messages, piece -> {
            full.append(piece);
            if (started[0]) {
                tokenConsumer.accept(piece);
            } else if (!full.toString().isBlank()) {
                started[0] = true;
                statusConsumer.accept("answering");
                tokenConsumer.accept(full.toString());
            }
        });
        if (!started[0]) {
            return emitAnswer(textOrFallback(null), statusConsumer, tokenConsumer);
        }
        return full.toString();
    }

    private String emitAnswer(String answer, Consumer<String> statusConsumer, Consumer<String> tokenConsumer) {
        statusConsumer.accept("answering");
        for (String chunk : chunkAnswer(answer)) {
            tokenConsumer.accept(chunk);
        }
        return answer;
    }

    /** 답변을 공백 경계 기준 조각으로 나눈다 — SSE token 이벤트 단위. 조각 연결 = 원문. */
    static List<String> chunkAnswer(String answer) {
        List<String> chunks = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\s*\\S+").matcher(answer);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            buffer.append(matcher.group());
            if (buffer.length() >= CHUNK_MIN_LENGTH) {
                chunks.add(buffer.toString());
                buffer.setLength(0);
            }
        }
        if (buffer.length() > 0) {
            chunks.add(buffer.toString());
        }
        return chunks;
    }

    /** 마지막 사용자 질문을 규칙 라우터에 넣어 도구를 실행하고 그 왕복을 컨텍스트에 추가한다. 실행했으면 true. */
    boolean applyFallback(List<Object> messages, Consumer<String> statusConsumer) {
        return applyFallback(messages, null, statusConsumer);
    }

    /** loadedSites가 null이면 필요할 때만 현장 목록을 조회한다. */
    private boolean applyFallback(List<Object> messages, List<IntentRouter.SiteRef> loadedSites,
                                  Consumer<String> statusConsumer) {
        String question = lastUserQuestion(messages);
        List<IntentRouter.SiteRef> sites = loadedSites != null ? loadedSites
                : IntentRouter.needsSites(question) ? loadSites() : List.of();
        List<IntentRouter.RoutedCall> routed = IntentRouter.route(question, sites);
        if (routed.isEmpty()) {
            return false;
        }
        List<Map<String, Object>> calls = routed.stream().map(IntentRouter::asToolCall).toList();
        Map<String, Object> assistant = new HashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", "");
        assistant.put("tool_calls", calls);
        messages.add(assistant);
        for (Map<String, Object> call : calls) {
            messages.add(toolResultMessage(objectMapper.valueToTree(call), statusConsumer));
        }
        log.info("도구 미선택 질문에 규칙 폴백 적용 tools={}", routed.stream().map(IntentRouter.RoutedCall::tool).toList());
        return true;
    }

    /**
     * 소형 모델은 "[QA] 1022-144647 현장"처럼 기호·숫자가 섞인 이름을 현장 목록에서 못 찾거나 siteId를 추측한다(2026-10-28 실측).
     * 현장별 도구가 필요한 질문이면 이름을 규칙으로 맞춰 siteId를 시스템 메시지로 알려 준다.
     * 조회한 현장 목록을 돌려줘 폴백이 다시 조회하지 않게 한다. 현장 질문이 아니면 null.
     */
    List<IntentRouter.SiteRef> addSiteHint(List<Object> messages) {
        String question = lastUserQuestion(messages);
        if (!IntentRouter.needsSites(question)) {
            return null;
        }
        List<IntentRouter.SiteRef> sites = loadSites();
        List<IntentRouter.SiteRef> mentioned = IntentRouter.mentionedSites(question, sites);
        if (!mentioned.isEmpty()) {
            // 이름은 관리자가 입력한 데이터라 지시로 읽히지 않게 JSON 데이터로만 넣고 제어문자·길이를 제한한다.
            List<Map<String, Object>> refs = mentioned.stream()
                    .map(site -> Map.<String, Object>of("siteId", site.id(), "siteName", hintName(site.name())))
                    .toList();
            try {
                messages.add(Map.of("role", "system", "content",
                        "질문에 언급된 현장 데이터: " + objectMapper.writeValueAsString(refs) + " 현장별 도구는 이 siteId로 호출한다."));
            } catch (Exception e) {
                log.debug("현장 힌트 생성 실패: {}", e.getClass().getSimpleName());
            }
        }
        return sites;
    }

    static String hintName(String name) {
        String clean = name.replaceAll("\\p{Cntrl}", " ").strip();
        return clean.length() > HINT_NAME_MAX ? clean.substring(0, HINT_NAME_MAX) : clean;
    }

    private static String lastUserQuestion(List<Object> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Map<?, ?> message && "user".equals(message.get("role"))) {
                Object content = message.get("content");
                return content == null ? "" : content.toString();
            }
        }
        return "";
    }

    private List<IntentRouter.SiteRef> loadSites() {
        try {
            JsonNode sites = objectMapper.readTree(toolExecutor.execute("listSites", null));
            List<IntentRouter.SiteRef> refs = new ArrayList<>();
            if (sites != null && sites.isArray()) {
                for (JsonNode site : sites) {
                    if (site.hasNonNull("id") && site.hasNonNull("siteName")) {
                        refs.add(new IntentRouter.SiteRef(site.get("id").asLong(), site.get("siteName").asText()));
                    }
                }
            }
            return refs;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 도구 호출 1건마다 결과 메시지 1건을 돌려준다 — 형식이 깨진 호출도 오류 결과로 응답해
     * 호출 수와 결과 수가 어긋나지 않게 한다. 모델이 호출 id를 주면 tool_call_id로,
     * 도구명은 Ollama 기본 필드 tool_name으로 함께 넣어 여러 호출의 결과를 섞지 않게 한다.
     */
    Map<String, Object> toolResultMessage(JsonNode call, Consumer<String> statusConsumer) {
        Map<String, Object> result = new HashMap<>();
        result.put("role", "tool");
        JsonNode id = call == null ? null : call.get("id");
        if (id != null && id.isTextual() && !id.asText().isBlank()) {
            result.put("tool_call_id", id.asText());
        }
        JsonNode fn = call == null ? null : call.get("function");
        JsonNode name = fn == null ? null : fn.get("name");
        if (name == null || !name.isTextual() || name.asText().isBlank()) {
            result.put("content", "{\"error\":\"도구 이름이 없는 호출은 실행하지 않았습니다\"}");
            return result;
        }
        statusConsumer.accept("tool");
        result.put("tool_name", name.asText());
        result.put("content", toolExecutor.execute(name.asText(), normalizeArgs(fn.get("arguments"))));
        return result;
    }

    private JsonNode normalizeArgs(JsonNode arguments) {
        if (arguments == null) {
            return null;
        }
        if (arguments.isTextual()) {
            try {
                return objectMapper.readTree(arguments.asText());
            } catch (Exception e) {
                return null;
            }
        }
        return arguments;
    }

    private String textOrFallback(JsonNode message) {
        if (message != null && message.hasNonNull("content")) {
            String content = message.get("content").asText();
            if (!content.isBlank()) {
                return content;
            }
        }
        return "죄송합니다. 답변을 생성하지 못했습니다. 질문을 조금 더 구체적으로 해주세요.";
    }

    private JsonNode callOllama(List<Object> messages, List<Map<String, Object>> tools) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("messages", messages);
        if (tools != null) {
            body.put("tools", tools);
        }
        try {
            JsonNode response = ollamaWebClient.post()
                    .uri("/api/chat")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            if (response == null || response.get("message") == null) {
                throw new BusinessException(ErrorCode.CHAT_LLM_FAILED);
            }
            return response.get("message");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Ollama 호출 실패", e);
            throw new BusinessException(ErrorCode.CHAT_LLM_FAILED);
        }
    }

    /**
     * 도구 없이 stream:true로 호출해 NDJSON 각 줄의 message.content를 pieceConsumer에 넘긴다.
     * 네트워크 청크는 줄·UTF-8 문자 경계와 무관하게 잘리므로 바이트를 누적해 개행(0x0A)에서만 자른다
     * — 0x0A는 UTF-8 다중 바이트 문자 안에 나타나지 않는다.
     * 조각 소비(SSE 전송)는 호출 스레드에서 하므로 소비자 예외(연결 끊김 등)는 변환 없이 그대로 전파된다.
     * timeout: 조각 사이 대기와 응답 전체 경과를 각각 timeoutSeconds로 제한한다.
     */
    private void streamOllama(List<Object> messages, Consumer<String> pieceConsumer) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("stream", true);
        body.put("messages", messages);
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        long deadline = System.nanoTime() + timeout.toNanos();
        List<String> lines = new ArrayList<>();
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        Stream<byte[]> chunks;
        try {
            chunks = ollamaWebClient.post()
                    .uri("/api/chat")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToFlux(DataBuffer.class)
                    .map(OllamaToolService::drain)
                    .timeout(timeout)
                    .toStream(1);
        } catch (Exception e) {
            log.error("Ollama 스트리밍 호출 실패", e);
            throw new BusinessException(ErrorCode.CHAT_LLM_FAILED);
        }
        try (chunks) {
            Iterator<byte[]> iterator = chunks.iterator();
            while (nextChunk(iterator, deadline)) {
                splitLines(iterator.next(), pending, lines);
                for (String line : lines) {
                    handleStreamLine(line, pieceConsumer);
                }
                lines.clear();
            }
        }
        handleStreamLine(pending.toString(StandardCharsets.UTF_8), pieceConsumer);
    }

    private static boolean nextChunk(Iterator<byte[]> iterator, long deadline) {
        try {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Ollama 스트리밍 응답 전체 시간 초과");
            }
            return iterator.hasNext();
        } catch (Exception e) {
            log.error("Ollama 스트리밍 호출 실패", e);
            throw new BusinessException(ErrorCode.CHAT_LLM_FAILED);
        }
    }

    private static byte[] drain(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    /** 개행 앞까지 누적된 바이트를 완성된 줄로 꺼낸다. 개행 뒤 잔여분은 pending에 남는다. */
    static void splitLines(byte[] bytes, ByteArrayOutputStream pending, List<String> lines) {
        for (byte b : bytes) {
            if (b == '\n') {
                lines.add(pending.toString(StandardCharsets.UTF_8));
                pending.reset();
            } else {
                pending.write(b);
            }
        }
    }

    private void handleStreamLine(String rawLine, Consumer<String> pieceConsumer) {
        String line = rawLine.trim();
        if (line.isEmpty()) {
            return;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(line);
        } catch (Exception e) {
            log.error("Ollama 스트리밍 응답 줄 해석 실패");
            throw new BusinessException(ErrorCode.CHAT_LLM_FAILED);
        }
        if (node.hasNonNull("error")) {
            log.error("Ollama 스트리밍 오류 응답: {}", node.get("error").asText());
            throw new BusinessException(ErrorCode.CHAT_LLM_FAILED);
        }
        JsonNode content = node.path("message").path("content");
        if (content.isTextual() && !content.asText().isEmpty()) {
            pieceConsumer.accept(content.asText());
        }
    }

}
