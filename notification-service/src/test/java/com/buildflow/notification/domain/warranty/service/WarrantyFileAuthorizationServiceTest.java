package com.buildflow.notification.domain.warranty.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.buildflow.notification.global.exception.BusinessException;
import com.buildflow.notification.global.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WarrantyFileAuthorizationServiceTest {
    private static final String SECRET = "synthetic-test-secret-at-least-32-chars-long";
    private StringRedisTemplate redis;
    private WarrantyFileAuthorizationService service;

    @BeforeEach
    void setup() {
        redis = mock(StringRedisTemplate.class);
        service = new WarrantyFileAuthorizationService(redis, SECRET);
    }

    @Test
    void 유효한_관리자_접근_토큰만_허용하고_블랙리스트가_없어야_한다() {
        String token = token("access", "ADMIN", 2);
        when(redis.hasKey("auth:blacklist:" + token)).thenReturn(false);
        assertDoesNotThrow(() -> service.requireAdminAccess("Bearer " + token));
    }

    @Test
    void 내부_직접_호출도_무인증_리프레시_구버전_폐기_토큰을_거부한다() {
        assertUnauthorized(null);
        assertUnauthorized("Bearer invalid");
        assertUnauthorized("Bearer " + token("refresh", "ADMIN", 2));
        assertUnauthorized("Bearer " + token("access", "VIEWER", 2));
        assertUnauthorized("Bearer " + token("access", "ADMIN", 1));
        String valid = token("access", "ADMIN", 2);
        when(redis.hasKey("auth:blacklist:" + valid)).thenReturn(true);
        assertUnauthorized("Bearer " + valid);
    }

    @Test
    void 레디스_조회_실패나_비밀값_누락은_거부한다() {
        String token = token("access", "ADMIN", 2);
        when(redis.hasKey(startsWith("auth:blacklist:"))).thenThrow(new IllegalStateException("redis unavailable"));
        assertUnauthorized("Bearer " + token);
        WarrantyFileAuthorizationService missingSecret = new WarrantyFileAuthorizationService(redis, "");
        assertEquals(ErrorCode.WARRANTY_FILE_UNAUTHORIZED, assertThrows(BusinessException.class,
                () -> missingSecret.requireAdminAccess("Bearer " + token)).getErrorCode());
    }

    private void assertUnauthorized(String header) {
        assertEquals(ErrorCode.WARRANTY_FILE_UNAUTHORIZED, assertThrows(BusinessException.class,
                () -> service.requireAdminAccess(header)).getErrorCode());
    }

    private String token(String type, String role, int version) {
        return Jwts.builder().subject("1").claim("type", type).claim("role", role)
                .claim("authVersion", version).expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }
}
