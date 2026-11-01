package com.buildflow.auth.global.jwt;

import com.buildflow.auth.global.exception.BusinessException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenProviderTest {

    private static final String SECRET = "auth-service-token-test-secret-must-be-at-least-32-bytes";
    private final JwtTokenProvider tokens = new JwtTokenProvider(SECRET, 1_800_000L, 604_800_000L, 604_800_000L, 30_000L);

    @Test
    void reuseGraceMustBeNonNegativeAndShorterThanRefreshTtl() {
        assertEquals(0L, new JwtTokenProvider(SECRET, 1_800_000L, 60_000L, 60_000L, 0L).getRefreshReuseGrace());
        assertEquals(59_999L, new JwtTokenProvider(SECRET, 1_800_000L, 60_000L, 60_000L, 59_999L).getRefreshReuseGrace());
        assertThrows(IllegalArgumentException.class,
                () -> new JwtTokenProvider(SECRET, 1_800_000L, 60_000L, 60_000L, 60_000L));
        assertThrows(IllegalArgumentException.class,
                () -> new JwtTokenProvider(SECRET, 1_800_000L, 60_000L, 60_000L, 120_000L));
        assertThrows(IllegalArgumentException.class,
                () -> new JwtTokenProvider(SECRET, 1_800_000L, 60_000L, 60_000L, -1L));
    }

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
        long now = System.currentTimeMillis();
        String token = tokens.generateRefreshToken(1L, "sid-1", now, now);

        assertEquals(1L, tokens.getUserId(token, "refresh"));
        assertThrows(BusinessException.class, () -> tokens.getUserId(token, "access"));
    }

    @Test
    void refreshTokenCarriesSessionAndNeverExpiresAfterAbsoluteLimit() {
        long now = System.currentTimeMillis();
        long startedAt = now - 604_800_000L + 60_000L; // 상한까지 1분 남음
        String token = tokens.generateRefreshToken(1L, "sid-1", startedAt, now);

        JwtTokenProvider.RefreshTokenClaims claims = tokens.parseRefreshToken(token);
        assertEquals("sid-1", claims.sessionId());
        assertEquals(startedAt, claims.sessionStartedAt());
        long exp = tokens.parseClaims(token).getExpiration().getTime();
        assertTrue(exp <= startedAt + 604_800_000L);
        assertNotEquals(token, tokens.generateRefreshToken(1L, "sid-1", startedAt, now), "jti로 같은 시각에도 다른 값");
    }

    @Test
    void rejectsVersionTwoRefreshWithoutSessionClaims() {
        String legacy = Jwts.builder()
                .subject("1")
                .claim("type", "refresh")
                .claim("authVersion", 2)
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertEquals(1L, tokens.getUserId(legacy, "refresh"));
        assertThrows(BusinessException.class, () -> tokens.parseRefreshToken(legacy));
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
