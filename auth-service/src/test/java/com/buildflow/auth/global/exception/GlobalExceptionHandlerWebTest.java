package com.buildflow.auth.global.exception;

import com.buildflow.auth.domain.user.controller.AuthController;
import com.buildflow.auth.domain.user.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerWebTest {

    @Test
    void unsupportedLoginMethodReturns405WithAllowAndNoAuthCall() throws Exception {
        AuthService authService = mock(AuthService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(get("/api/v1/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "POST"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("지원하지 않는 요청 방식입니다."));

        verifyNoInteractions(authService);
    }
}
