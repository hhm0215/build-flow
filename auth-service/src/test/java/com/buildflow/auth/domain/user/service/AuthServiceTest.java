package com.buildflow.auth.domain.user.service;

import com.buildflow.auth.domain.user.dto.LoginRequest;
import com.buildflow.auth.domain.user.dto.RefreshRequest;
import com.buildflow.auth.domain.user.dto.TokenResponse;
import com.buildflow.auth.domain.user.entity.User;
import com.buildflow.auth.domain.user.repository.UserRepository;
import com.buildflow.auth.domain.user.service.RefreshSessionStore.RotationResult;
import com.buildflow.auth.global.exception.BusinessException;
import com.buildflow.auth.global.exception.ErrorCode;
import com.buildflow.auth.global.jwt.JwtTokenProvider;
import com.buildflow.auth.global.jwt.JwtTokenProvider.RefreshTokenClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String SECRET = "auth-service-token-test-secret-must-be-at-least-32-bytes";
    private static final long NOW = 1_800_000_000_000L;
    private static final long REFRESH_TTL = 604_800_000L;
    private static final long ABSOLUTE = 604_800_000L;
    private static final long GRACE = 30_000L;

    @Mock UserRepository userRepository;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;
    @Mock BCryptPasswordEncoder encoder;
    @Mock RefreshSessionStore sessions;

    private final JwtTokenProvider tokens = new JwtTokenProvider(SECRET, 1_800_000L, REFRESH_TTL, ABSOLUTE, GRACE);
    private AuthService authService;
    private final User admin = User.builder().loginId("admin").password("hash").name("관리자").build();

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, tokens, redis, encoder, sessions,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @Test
    void loginStartsNewSessionWithAbsoluteStartAndTtl() {
        when(userRepository.findByLoginId("admin")).thenReturn(Optional.of(admin));
        when(encoder.matches("password", "hash")).thenReturn(true);

        TokenResponse response = authService.login(new LoginRequest("admin", "password"));

        RefreshTokenClaims claims = tokens.parseRefreshToken(response.refreshToken());
        assertEquals(1L, claims.userId());
        assertEquals(NOW, claims.sessionStartedAt());
        assertEquals(1L, tokens.getUserId(response.accessToken(), "access"));
        verify(sessions).start(1L, claims.sessionId(), response.refreshToken(), REFRESH_TTL);
    }

    @Test
    void rejectsWrongPasswordWithoutIssuingTokens() {
        when(userRepository.findByLoginId("admin")).thenReturn(Optional.of(admin));
        when(encoder.matches("wrong", "hash")).thenReturn(false);

        assertThrows(BusinessException.class, () -> authService.login(new LoginRequest("admin", "wrong")));
        verifyNoInteractions(sessions, redis);
    }

    @Test
    void refreshRotatesWithinSameSessionWithoutExtendingAbsoluteStart() {
        long startedAt = NOW - 86_400_000L; // 하루 전 로그인
        String old = tokens.generateRefreshToken(1L, "sid-1", startedAt, NOW - 60_000L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        long expectedTtl = startedAt + ABSOLUTE - NOW;
        when(sessions.rotate(eq(1L), eq("sid-1"), eq(old), anyString(), eq(expectedTtl), eq(NOW), eq(GRACE)))
                .thenReturn(RotationResult.ROTATED);

        TokenResponse response = authService.refresh(new RefreshRequest(old));

        RefreshTokenClaims rotated = tokens.parseRefreshToken(response.refreshToken());
        assertEquals("sid-1", rotated.sessionId());
        assertEquals(startedAt, rotated.sessionStartedAt());
        assertNotEquals(old, response.refreshToken());
        long exp = tokens.parseClaims(response.refreshToken()).getExpiration().getTime();
        assertTrue(exp <= startedAt + ABSOLUTE, "회전한 refresh 만료가 절대 상한을 넘지 않는다");
        ArgumentCaptor<String> issued = ArgumentCaptor.forClass(String.class);
        verify(sessions).rotate(eq(1L), eq("sid-1"), eq(old), issued.capture(), eq(expectedTtl), eq(NOW), eq(GRACE));
        assertEquals(response.refreshToken(), issued.getValue());
    }

    @Test
    void refreshAfterAbsoluteLimitIsRejectedWithoutTouchingRedis() {
        // 서명은 유효하지만 최초 로그인이 상한보다 오래됐다(토큰 만료 전 시계 오차·설정 축소 대비).
        JwtTokenProvider longLived = new JwtTokenProvider(SECRET, 1_800_000L, REFRESH_TTL, ABSOLUTE * 10, GRACE);
        String stale = longLived.generateRefreshToken(1L, "sid-1", NOW - ABSOLUTE - 1, NOW - 1000L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> authService.refresh(new RefreshRequest(stale)));

        assertEquals(ErrorCode.SESSION_EXPIRED, error.getErrorCode());
        verifyNoInteractions(sessions, userRepository);
    }

    @Test
    void reusedRefreshIsReportedAsReuse() {
        String old = tokens.generateRefreshToken(1L, "sid-1", NOW - 1000L, NOW - 1000L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(sessions.rotate(eq(1L), eq("sid-1"), eq(old), anyString(), anyLong(), eq(NOW), eq(GRACE)))
                .thenReturn(RotationResult.REUSED);

        BusinessException error = assertThrows(BusinessException.class,
                () -> authService.refresh(new RefreshRequest(old)));

        assertEquals(ErrorCode.REFRESH_TOKEN_REUSED, error.getErrorCode());
        assertEquals(401, error.getErrorCode().getStatus().value());
    }

    @ParameterizedTest
    @EnumSource(value = RotationResult.class, names = {"NOT_FOUND", "OTHER_SESSION"})
    void nonRotatedResultsAreUnauthorizedWithoutNewTokens(RotationResult result) {
        String old = tokens.generateRefreshToken(1L, "sid-1", NOW - 1000L, NOW - 1000L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(sessions.rotate(eq(1L), eq("sid-1"), eq(old), anyString(), anyLong(), eq(NOW), eq(GRACE)))
                .thenReturn(result);

        BusinessException error = assertThrows(BusinessException.class,
                () -> authService.refresh(new RefreshRequest(old)));

        assertEquals(ErrorCode.REFRESH_TOKEN_NOT_FOUND, error.getErrorCode());
    }

    @Test
    void concurrentRotationWithinGraceIsConflictNotRevocation() {
        String old = tokens.generateRefreshToken(1L, "sid-1", NOW - 1000L, NOW - 1000L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(sessions.rotate(eq(1L), eq("sid-1"), eq(old), anyString(), anyLong(), eq(NOW), eq(GRACE)))
                .thenReturn(RotationResult.CONCURRENT);

        BusinessException error = assertThrows(BusinessException.class,
                () -> authService.refresh(new RefreshRequest(old)));

        assertEquals(ErrorCode.REFRESH_IN_PROGRESS, error.getErrorCode());
        assertEquals(409, error.getErrorCode().getStatus().value());
        verify(sessions, never()).revoke(1L);
    }

    @Test
    void accessTokenCannotBeUsedAsRefresh() {
        String access = tokens.generateAccessToken(1L, "admin");

        assertThrows(BusinessException.class, () -> authService.refresh(new RefreshRequest(access)));
        verifyNoInteractions(sessions);
    }

    @Test
    void logoutValidatesAccessTypeThenRevokesTokens() {
        String access = tokens.generateAccessToken(1L, "admin");
        when(redis.opsForValue()).thenReturn(values);

        authService.logout(access);

        verify(values).set(eq("auth:blacklist:" + access), eq("logout"), anyLong(), eq(TimeUnit.MILLISECONDS));
        verify(sessions).revoke(1L);
    }

    @Test
    void logoutRejectsRefreshTokenWithoutRevoking() {
        String refresh = tokens.generateRefreshToken(1L, "sid-1", NOW, NOW);

        assertThrows(BusinessException.class, () -> authService.logout(refresh));
        verify(sessions, never()).revoke(1L);
    }
}
