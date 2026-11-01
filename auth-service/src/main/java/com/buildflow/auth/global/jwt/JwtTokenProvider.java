package com.buildflow.auth.global.jwt;

import com.buildflow.auth.global.exception.BusinessException;
import com.buildflow.auth.global.exception.ErrorCode;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Component
public class JwtTokenProvider {

    private static final int AUTH_VERSION = 2;
    static final String SESSION_ID_CLAIM = "sid";
    static final String SESSION_STARTED_AT_CLAIM = "sst";

    private final SecretKey secretKey;
    private final long accessTokenExpiration;
    private final long refreshTokenExpiration;
    private final long sessionAbsoluteExpiration;
    private final long refreshReuseGrace;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiration}") long accessTokenExpiration,
            @Value("${jwt.refresh-token-expiration}") long refreshTokenExpiration,
            @Value("${jwt.session-absolute-expiration:604800000}") long sessionAbsoluteExpiration,
            @Value("${jwt.refresh-reuse-grace:10000}") long refreshReuseGrace
    ) {
        if (refreshTokenExpiration <= 0 || sessionAbsoluteExpiration <= 0) {
            throw new IllegalArgumentException("refresh/session expiration must be positive");
        }
        // 유예가 refresh TTL 이상이면 직전 토큰 재사용이 사실상 탐지되지 않는다 — 잘못된 설정은 기동 실패로 막는다.
        if (refreshReuseGrace < 0 || refreshReuseGrace >= refreshTokenExpiration) {
            throw new IllegalArgumentException("jwt.refresh-reuse-grace must be >= 0 and < jwt.refresh-token-expiration");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiration = accessTokenExpiration;
        this.refreshTokenExpiration = refreshTokenExpiration;
        this.sessionAbsoluteExpiration = sessionAbsoluteExpiration;
        this.refreshReuseGrace = refreshReuseGrace;
    }

    public String generateAccessToken(Long userId, String loginId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("loginId", loginId)
                .claim("role", "ADMIN")
                .claim("type", "access")
                .claim("authVersion", AUTH_VERSION)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessTokenExpiration))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 세션(최초 로그인) 단위 refresh 토큰. 회전해도 sid·sst를 그대로 옮겨 절대 상한을 늘리지 않는다.
     * 만료는 min(now + refresh TTL, sst + 절대 상한). jti로 같은 초에 회전해도 토큰 값이 겹치지 않는다.
     */
    public String generateRefreshToken(Long userId, String sessionId, long sessionStartedAt, long nowMillis) {
        long expiresAt = Math.min(nowMillis + refreshTokenExpiration, sessionStartedAt + sessionAbsoluteExpiration);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString())
                .claim("type", "refresh")
                .claim("authVersion", AUTH_VERSION)
                .claim(SESSION_ID_CLAIM, sessionId)
                .claim(SESSION_STARTED_AT_CLAIM, sessionStartedAt)
                .issuedAt(new Date(nowMillis))
                .expiration(new Date(expiresAt))
                .signWith(secretKey)
                .compact();
    }

    public Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
    }

    public Long getUserId(String token, String expectedType) {
        return userIdOf(validatedClaims(token, expectedType));
    }

    /** sid·sst가 없는 이전 형식 refresh는 거부한다(배포 후 기존 세션은 재로그인 1회). */
    public RefreshTokenClaims parseRefreshToken(String token) {
        Claims claims = validatedClaims(token, "refresh");
        String sessionId = claims.get(SESSION_ID_CLAIM, String.class);
        Object startedAt = claims.get(SESSION_STARTED_AT_CLAIM);
        if (sessionId == null || sessionId.isBlank() || sessionId.contains("|") || !(startedAt instanceof Number)) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        return new RefreshTokenClaims(userIdOf(claims), sessionId, ((Number) startedAt).longValue());
    }

    private Claims validatedClaims(String token, String expectedType) {
        Claims claims = parseClaims(token);
        Object version = claims.get("authVersion");
        if (!expectedType.equals(claims.get("type", String.class))
                || !(version instanceof Number)
                || ((Number) version).intValue() != AUTH_VERSION) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        return claims;
    }

    private static Long userIdOf(Claims claims) {
        try {
            return Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
    }

    public long getRefreshTokenExpiration() {
        return refreshTokenExpiration;
    }

    public long getSessionAbsoluteExpiration() {
        return sessionAbsoluteExpiration;
    }

    public long getRefreshReuseGrace() {
        return refreshReuseGrace;
    }

    public long getAccessTokenExpiration() {
        return accessTokenExpiration;
    }

    public long getRemainingExpiration(String token) {
        Date expiration = parseClaims(token).getExpiration();
        return expiration.getTime() - System.currentTimeMillis();
    }

    public record RefreshTokenClaims(Long userId, String sessionId, long sessionStartedAt) {
    }
}
