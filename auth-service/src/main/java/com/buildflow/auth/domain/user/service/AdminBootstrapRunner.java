package com.buildflow.auth.domain.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.admin-bootstrap.enabled", havingValue = "true")
public class AdminBootstrapRunner implements ApplicationRunner {

    private final AdminBootstrapService bootstrapService;
    private final ObjectMapper objectMapper;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!"none".equalsIgnoreCase(environment.getProperty("spring.main.web-application-type"))) {
            throw new IllegalStateException("관리자 초기화는 비웹 로컬 명령에서만 실행할 수 있습니다.");
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String input = reader.readLine();
        if (input == null || input.length() > 4096) {
            throw new IllegalArgumentException("관리자 초기화 입력이 없거나 너무 깁니다.");
        }

        BootstrapInput request;
        try {
            request = objectMapper.readValue(input, BootstrapInput.class);
        } catch (Exception e) {
            // 파서 오류에 원본 JSON(비밀번호)이 포함되지 않도록 cause를 전달하지 않는다.
            throw new IllegalArgumentException("관리자 초기화 입력 형식이 올바르지 않습니다.");
        }
        bootstrapService.create(request.loginId(), request.name(), request.password());
        System.out.println("관리자 계정을 생성했습니다.");
    }

    private record BootstrapInput(String loginId, String name, String password) {}
}
