package com.buildflow.notification.domain.warranty.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import com.buildflow.notification.global.exception.BusinessException;
import com.buildflow.notification.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/** 파일 읽기는 내부 포트 직접 호출에도 Gateway와 동일한 관리자 인증·토큰 폐기를 적용한다. */
@Service
public class WarrantyFileAuthorizationService {
    private final StringRedisTemplate redis;
    private final String secret;

    public WarrantyFileAuthorizationService(StringRedisTemplate redis,
            @Value("${app.warranty-file-jwt-secret:}") String secret) {
        this.redis = redis;
        this.secret = secret;
    }

    public void requireAdminAccess(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || secret.isBlank()) {
            throw unauthorized();
        }
        String token = authorization.substring(7);
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                    .build().parseSignedClaims(token).getPayload();
            Object version = claims.get("authVersion");
            if (!"access".equals(claims.get("type", String.class))
                    || !"ADMIN".equals(claims.get("role", String.class))
                    || !(version instanceof Number) || ((Number) version).intValue() != 2
                    || claims.getSubject() == null || claims.getSubject().isBlank()
                    || !Boolean.FALSE.equals(redis.hasKey("auth:blacklist:" + token))) {
                throw unauthorized();
            }
        } catch (Exception e) {
            throw unauthorized();
        }
    }

    private BusinessException unauthorized() {
        return new BusinessException(ErrorCode.WARRANTY_FILE_UNAUTHORIZED);
    }
}
