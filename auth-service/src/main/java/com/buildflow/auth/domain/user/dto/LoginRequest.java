package com.buildflow.auth.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record LoginRequest(
        @NotBlank(message = "관리자 아이디는 필수입니다.")
        @Pattern(regexp = "[A-Za-z0-9._-]{3,50}", message = "관리자 아이디 형식이 올바르지 않습니다.")
        String loginId,

        @NotBlank(message = "비밀번호는 필수입니다.")
        String password
) {}
