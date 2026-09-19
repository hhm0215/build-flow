package com.buildflow.auth.domain.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AdminBootstrapRunnerTest {

    private final AdminBootstrapService service = mock(AdminBootstrapService.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final AdminBootstrapRunner runner = new AdminBootstrapRunner(service, new ObjectMapper(), environment);

    @Test
    void acceptsJsonOnlyInNonWebMode() throws Exception {
        environment.setProperty("spring.main.web-application-type", "none");
        withStdin("{\"loginId\":\"admin\",\"name\":\"관리자\",\"password\":\"secure-password\"}\n", () ->
                runner.run(new DefaultApplicationArguments()));

        verify(service).create("admin", "관리자", "secure-password");
    }

    @Test
    void rejectsWebModeBeforeReadingCredentials() {
        environment.setProperty("spring.main.web-application-type", "servlet");
        assertThrows(IllegalStateException.class, () -> runner.run(new DefaultApplicationArguments()));
        verifyNoInteractions(service);
    }

    @Test
    void malformedInputDoesNotReachAccountService() {
        environment.setProperty("spring.main.web-application-type", "none");
        assertThrows(IllegalArgumentException.class, () -> withStdin("{bad json}\n", () ->
                runner.run(new DefaultApplicationArguments())));
        verifyNoInteractions(service);
    }

    private static void withStdin(String value, ThrowingRunnable action) throws Exception {
        InputStream original = System.in;
        try {
            System.setIn(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
            action.run();
        } finally {
            System.setIn(original);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
