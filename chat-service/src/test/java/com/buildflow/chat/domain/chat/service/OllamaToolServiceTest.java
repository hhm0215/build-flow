package com.buildflow.chat.domain.chat.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaToolServiceTest {

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
