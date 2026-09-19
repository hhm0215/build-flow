package com.buildflow.auth.global.jwt;

import com.buildflow.auth.global.exception.BusinessException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtTokenProviderTest {

    private static final String SECRET = "auth-service-token-test-secret-must-be-at-least-32-bytes";
    private final JwtTokenProvider tokens = new JwtTokenProvider(SECRET, 1_800_000L, 604_800_000L);

    @Test
    void accessTokenUsesAdminLoginIdAndVersionTwo() {
        String token = tokens.generateAccessToken(1L, "admin");

        assertEquals(1L, tokens.getUserId(token, "access"));
        assertEquals("admin", tokens.parseClaims(token).get("loginId", String.class));
        assertEquals("ADMIN", tokens.parseClaims(token).get("role", String.class));
        assertEquals(2, ((Number) tokens.parseClaims(token).get("authVersion")).intValue());
        assertThrows(BusinessException.class, () -> tokens.getUserId(token, "refresh"));
    }

    @Test
    void refreshTokenCannotBeUsedAsAccessToken() {
        String token = tokens.generateRefreshToken(1L);

        assertEquals(1L, tokens.getUserId(token, "refresh"));
        assertThrows(BusinessException.class, () -> tokens.getUserId(token, "access"));
    }

    @Test
    void rejectsLegacyTokenWithoutVersionClaim() {
        String token = Jwts.builder()
                .subject("1")
                .claim("type", "refresh")
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThrows(BusinessException.class, () -> tokens.getUserId(token, "refresh"));
    }

    @Test
    void rejectsBlankTokenAsInvalidCredentials() {
        assertThrows(BusinessException.class, () -> tokens.getUserId("", "access"));
    }
}
