package com.buildflow.estimate.global.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UnreadableRequestHandlerTest {

    @RestController
    static class EchoController {
        @PostMapping("/echo")
        Map<String, Object> echo(@RequestBody Map<String, Object> body) {
            return body;
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new EchoController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void 깨진_UTF8_본문은_400을_반환한다() throws Exception {
        byte[] cp949Body = {'{', '"', 'a', '"', ':', '"', (byte) 0xC7, (byte) 0xD1, '"', '}'};

        mockMvc.perform(post("/echo").contentType(MediaType.APPLICATION_JSON).content(cp949Body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("요청 본문이 올바르지 않습니다."));
    }

    @Test
    void 형식이_틀린_JSON은_400을_반환한다() throws Exception {
        mockMvc.perform(post("/echo").contentType(MediaType.APPLICATION_JSON).content("{\"a\":"))
                .andExpect(status().isBadRequest());
    }
}
