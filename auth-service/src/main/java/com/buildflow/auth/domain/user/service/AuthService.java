package com.buildflow.auth.domain.user.service;

import com.buildflow.auth.domain.user.dto.*;
import com.buildflow.auth.domain.user.entity.User;
import com.buildflow.auth.domain.user.repository.UserRepository;
import com.buildflow.auth.global.exception.BusinessException;
import com.buildflow.auth.global.exception.ErrorCode;
import com.buildflow.auth.global.jwt.JwtTokenProvider;
import com.buildflow.auth.global.jwt.JwtTokenProvider.RefreshTokenClaims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private static final String BLACKLIST_PREFIX = "auth:blacklist:";

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;
    private final BCryptPasswordEncoder passwordEncoder;
    private final RefreshSessionStore refreshSessionStore;
    private final Clock clock;

    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByLoginId(request.loginId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        if (!Long.valueOf(1L).equals(user.getId())
                || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        long now = clock.millis();
        String sessionId = UUID.randomUUID().toString();
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getLoginId());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), sessionId, now, now);

        refreshSessionStore.start(user.getId(), sessionId, refreshToken, refreshTtl(now, now));

        return TokenResponse.of(accessToken, refreshToken, jwtTokenProvider.getAccessTokenExpiration());
    }

    public TokenResponse refresh(RefreshRequest request) {
        String refreshToken = request.refreshToken();
        RefreshTokenClaims claims = jwtTokenProvider.parseRefreshToken(refreshToken);
        Long userId = claims.userId();

        long now = clock.millis();
        long ttl = refreshTtl(claims.sessionStartedAt(), now);
        if (ttl <= 0) {
            // 회전해도 최초 로그인 시각은 그대로라 절대 상한을 넘으면 재로그인만 가능하다.
            throw new BusinessException(ErrorCode.SESSION_EXPIRED);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!Long.valueOf(1L).equals(user.getId())) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }

        String newAccessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getLoginId());
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(
                user.getId(), claims.sessionId(), claims.sessionStartedAt(), now);

        RefreshSessionStore.RotationResult result = refreshSessionStore.rotate(
                userId, claims.sessionId(), refreshToken, newRefreshToken, ttl, now,
                jwtTokenProvider.getRefreshReuseGrace());
        switch (result) {
            case ROTATED -> {
                return TokenResponse.of(newAccessToken, newRefreshToken, jwtTokenProvider.getAccessTokenExpiration());
            }
            case REUSED -> {
                // 이미 회전된 refresh가 유예 밖에서 다시 들어옴 = 탈취 가능성. 스크립트가 세션 키를 폐기했다.
                log.warn("Refresh token reuse detected; session revoked for userId={}", userId);
                throw new BusinessException(ErrorCode.REFRESH_TOKEN_REUSED);
            }
            // 회전 직후 다른 탭이 같은 직전 토큰으로 재발급 — 세션을 끊지 않고 409로 알린다(프론트는 저장소의 새 토큰을 기다린다).
            case CONCURRENT -> throw new BusinessException(ErrorCode.REFRESH_IN_PROGRESS);
            default -> throw new BusinessException(ErrorCode.REFRESH_TOKEN_NOT_FOUND);
        }
    }

    public void logout(String accessToken) {
        Long userId = jwtTokenProvider.getUserId(accessToken, "access");
        long remaining = jwtTokenProvider.getRemainingExpiration(accessToken);
        if (remaining > 0) {
            redisTemplate.opsForValue().set(
                    BLACKLIST_PREFIX + accessToken,
                    "logout",
                    remaining,
                    TimeUnit.MILLISECONDS
            );
        }

        refreshSessionStore.revoke(userId);
    }

    /** refresh 키 TTL = min(refresh TTL, 최초 로그인 + 절대 상한 - 지금). 0 이하이면 세션 만료. */
    private long refreshTtl(long sessionStartedAt, long now) {
        long remaining = sessionStartedAt + jwtTokenProvider.getSessionAbsoluteExpiration() - now;
        return Math.min(jwtTokenProvider.getRefreshTokenExpiration(), remaining);
    }
}
