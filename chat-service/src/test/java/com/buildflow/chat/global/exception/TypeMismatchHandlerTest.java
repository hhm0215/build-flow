package com.buildflow.chat.global.exception;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TypeMismatchHandlerTest {

    @RestController
    static class ProbeController {
        @GetMapping("/probe/{id}")
        Long byId(@PathVariable Long id) {
            return id;
        }

        @GetMapping("/probe")
        Long byQuery(@RequestParam Long siteId) {
            return siteId;
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void 숫자가_아닌_경로_변수는_400을_반환하고_입력값을_반사하지_않는다() throws Exception {
        mockMvc.perform(get("/probe/{id}", "dashboard<script>"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("요청 값 형식이 올바르지 않습니다: id"))
                .andExpect(jsonPath("$.error").value(not(containsString("dashboard"))));
    }

    @Test
    void 숫자가_아닌_쿼리_파라미터는_400을_반환한다() throws Exception {
        mockMvc.perform(get("/probe").param("siteId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("요청 값 형식이 올바르지 않습니다: siteId"));
    }

    @Test
    void 필수_쿼리_파라미터가_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/probe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("필수 요청 값이 없습니다: siteId"));
    }
}
