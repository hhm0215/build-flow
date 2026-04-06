package com.buildflow.auth.domain.user.controller;

import com.buildflow.auth.domain.user.dto.LoginRequest;
import com.buildflow.auth.domain.user.dto.RefreshRequest;
import com.buildflow.auth.domain.user.dto.SignUpRequest;
import com.buildflow.auth.domain.user.dto.TokenResponse;
import com.buildflow.auth.domain.user.service.AuthService;
import com.buildflow.auth.global.exception.BusinessException;
import com.buildflow.auth.global.exception.ErrorCode;
import com.buildflow.auth.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<Void>> signUp(@Valid @RequestBody SignUpRequest request) {
        authService.signUp(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success());
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request) {
        TokenResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
        TokenResponse response = authService.refresh(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@RequestHeader("Authorization") String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        String token = authorization.substring(7);
        authService.logout(token);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
