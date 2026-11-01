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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 회전 Lua 스크립트를 실제 Redis에서 실행한다. Mockito 단위 테스트는 스크립트 문자열·인자만 고정하므로
 * Lua 패턴·분기·원자성은 이 테스트가 증명한다.
 *
 * <p>CI 잡 {@code auth-redis}(Redis 서비스 컨테이너)에서만 실행된다. 로컬에서는 임시 Redis 컨테이너를 띄우고
 * {@code AUTH_REDIS_INTEGRATION=true REDIS_HOST=127.0.0.1 REDIS_PORT=<포트>}로 켠다. 전용 DB 인덱스(15)의
 * 이 테스트가 만든 키만 지운다 — 운영·개발 Redis에 붙이지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "AUTH_REDIS_INTEGRATION", matches = "true")
@EnabledIfEnvironmentVariable(named = "REDIS_HOST", matches = "^127\\.0\\.0\\.1$")
class RefreshSessionStoreRedisIntegrationTest {

    private static final int TEST_DB = 15;
    private static final long TTL = 600_000L;
    private static final long GRACE = 10_000L;
    private static final String SID = "sid-it";
    private static final List<Long> USER_IDS = List.of(1L, 1001L, 1002L, 1003L, 1004L, 1005L, 1006L, 1007L, 1008L);

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    private RefreshSessionStore store;
    private long now;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                System.getenv("REDIS_HOST"), Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
        config.setDatabase(TEST_DB);
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        cleanKeys();
        store = new RefreshSessionStore(redis);
        now = System.currentTimeMillis();
    }

    @AfterEach
    void cleanKeys() {
        USER_IDS.forEach(id -> redis.delete(RefreshSessionStore.key(id)));
    }

    @Test
    void rotationReplacesCurrentHashKeepsPreviousAndBoundsTtl() {
        store.start(1001L, SID, "t0", TTL);

        assertEquals(RotationResult.ROTATED, store.rotate(1001L, SID, "t0", "t1", TTL / 2, now, GRACE));

        assertEquals(RefreshSessionStore.encode(SID, RefreshSessionStore.hash("t1"), RefreshSessionStore.hash("t0"),
                String.valueOf(now)), value(1001L));
        long pttl = pttl(1001L);
        assertTrue(pttl > 0 && pttl <= TTL / 2, "회전 후 TTL은 넘긴 TTL 이하: " + pttl);
        // 새 현재 토큰으로 다시 회전 가능
        assertEquals(RotationResult.ROTATED, store.rotate(1001L, SID, "t1", "t2", TTL, now + 1, GRACE));
    }

    @Test
    void previousTokenWithinGraceIsConcurrentAndKeepsKey() {
        store.start(1002L, SID, "t0", TTL);
        store.rotate(1002L, SID, "t0", "t1", TTL, now, GRACE);
        String before = value(1002L);

        assertEquals(RotationResult.CONCURRENT, store.rotate(1002L, SID, "t0", "tx", TTL, now + GRACE, GRACE));

        assertEquals(before, value(1002L));
        assertEquals(RotationResult.ROTATED, store.rotate(1002L, SID, "t1", "t2", TTL, now + GRACE, GRACE));
    }

    @Test
    void previousTokenOutsideGraceRevokesSessionAndLatestTokenThenFails() {
        store.start(1003L, SID, "t0", TTL);
        store.rotate(1003L, SID, "t0", "t1", TTL, now, GRACE);

        assertEquals(RotationResult.REUSED, store.rotate(1003L, SID, "t0", "tx", TTL, now + GRACE + 1, GRACE));

        assertFalse(Boolean.TRUE.equals(redis.hasKey(RefreshSessionStore.key(1003L))), "재사용 탐지 시 키 폐기");
        assertEquals(RotationResult.NOT_FOUND, store.rotate(1003L, SID, "t1", "t2", TTL, now + GRACE + 2, GRACE));
    }

    @Test
    void unknownTokenOfSameSessionIsTreatedAsReuse() {
        store.start(1003L, SID, "t0", TTL);

        assertEquals(RotationResult.REUSED, store.rotate(1003L, SID, "forged", "tx", TTL, now, GRACE));
        assertNull(value(1003L));
    }

    @Test
    void otherSessionIdIsRejectedWithoutChange() {
        store.start(1004L, SID, "t0", TTL);
        String before = value(1004L);

        assertEquals(RotationResult.OTHER_SESSION, store.rotate(1004L, "sid-other", "t0", "tx", TTL, now, GRACE));

        assertEquals(before, value(1004L));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 이전 형식: 원문 JWT 그대로 저장
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl",
            "v2|sid-it|aaa|bbb|1",
            "v3|sid-it",
            "v3|sid-it|aaa|bbb|not-a-number",
            "v3|sid-it|aaa|bbb|1|extra",
            "v3||aaa||",
            ""
    })
    void legacyOrMalformedValueIsRejectedAndLeftUnchanged(String stored) {
        redis.opsForValue().set(RefreshSessionStore.key(1005L), stored, TTL, TimeUnit.MILLISECONDS);
        long ttlBefore = pttl(1005L);

        assertEquals(RotationResult.OTHER_SESSION, store.rotate(1005L, SID, "t0", "tx", TTL / 2, now, GRACE));

        assertEquals(stored, value(1005L));
        long ttlAfter = pttl(1005L);
        assertTrue(ttlAfter > 0 && ttlAfter <= ttlBefore, "TTL도 건드리지 않는다: " + ttlBefore + " -> " + ttlAfter);
    }

    @Test
    void missingKeyIsNotFoundAndNothingIsCreated() {
        assertEquals(RotationResult.NOT_FOUND, store.rotate(1006L, SID, "t0", "tx", TTL, now, GRACE));
        assertNull(value(1006L));
    }

    @Test
    void sameTokenTwiceInParallelYieldsOneRotationAndOneConflict() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 20; i++) {
                store.start(1007L, SID, "t0", TTL);
                CountDownLatch go = new CountDownLatch(1);
                long at = now + i;
                Callable<RotationResult> a = () -> { go.await(); return store.rotate(1007L, SID, "t0", "ta" + i(at), TTL, at, GRACE); };
                Callable<RotationResult> b = () -> { go.await(); return store.rotate(1007L, SID, "t0", "tb" + i(at), TTL, at, GRACE); };
                Future<RotationResult> fa = pool.submit(a);
                Future<RotationResult> fb = pool.submit(b);
                go.countDown();
                List<RotationResult> results = new ArrayList<>(List.of(fa.get(5, TimeUnit.SECONDS), fb.get(5, TimeUnit.SECONDS)));
                results.sort(null);

                assertEquals(List.of(RotationResult.ROTATED, RotationResult.CONCURRENT), results, "iteration " + i);
                String winner = fa.get() == RotationResult.ROTATED ? "ta" + i(at) : "tb" + i(at);
                assertEquals(RotationResult.ROTATED, store.rotate(1007L, SID, winner, "next", TTL, at, GRACE),
                        "승자 토큰은 계속 유효");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void logoutDeleteRacingRotationNeverResurrectsSession() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int resurrected = 0;
        try {
            for (int i = 0; i < 200; i++) {
                store.start(1008L, SID, "t0", TTL);
                CountDownLatch go = new CountDownLatch(1);
                long at = now + i;
                Future<RotationResult> rotation = pool.submit(() -> {
                    go.await();
                    return store.rotate(1008L, SID, "t0", "t1", TTL, at, GRACE);
                });
                Future<?> logout = pool.submit(() -> {
                    go.await();
                    store.revoke(1008L);
                    return null;
                });
                go.countDown();
                RotationResult result = rotation.get(5, TimeUnit.SECONDS);
                logout.get(5, TimeUnit.SECONDS);

                assertTrue(result == RotationResult.ROTATED || result == RotationResult.NOT_FOUND, result.name());
                if (value(1008L) != null) {
                    resurrected++;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, resurrected, "로그아웃 뒤 refresh 세션이 되살아난 횟수");
    }

    @Test
    void serviceRefreshTtlNeverExceedsAbsoluteSessionCapAndMapsErrors() {
        long absolute = 3_600_000L;
        long refreshTtl = 7_200_000L; // 절대 상한보다 긴 refresh TTL — Redis TTL은 상한 쪽으로 잘려야 한다
        JwtTokenProvider tokens = new JwtTokenProvider(
                "auth-redis-integration-test-secret-at-least-32-bytes", 1_800_000L, refreshTtl, absolute, GRACE);
        UserRepository users = mock(UserRepository.class);
        BCryptPasswordEncoder encoder = mock(BCryptPasswordEncoder.class);
        User admin = User.builder().loginId("admin").password("hash").name("관리자").build();
        when(users.findByLoginId("admin")).thenReturn(Optional.of(admin));
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        when(encoder.matches("pw", "hash")).thenReturn(true);

        long start = now;
        TokenResponse login = service(tokens, users, encoder, start)
                .login(new LoginRequest("admin", "pw"));
        long afterLogin = pttl(1L);
        assertTrue(afterLogin > 0 && afterLogin <= absolute, "로그인 TTL ≤ 절대 상한: " + afterLogin);

        long later = start + absolute * 6 / 10;
        TokenResponse rotated = service(tokens, users, encoder, later).refresh(new RefreshRequest(login.refreshToken()));
        long afterRotate = pttl(1L);
        assertTrue(afterRotate > 0 && afterRotate <= absolute - (later - start),
                "회전 후 TTL ≤ 남은 절대 상한: " + afterRotate);

        // 유예 내 직전 토큰 → 409, 키 유지
        BusinessException conflict = assertThrows(BusinessException.class,
                () -> service(tokens, users, encoder, later + GRACE).refresh(new RefreshRequest(login.refreshToken())));
        assertEquals(ErrorCode.REFRESH_IN_PROGRESS, conflict.getErrorCode());
        assertTrue(Boolean.TRUE.equals(redis.hasKey(RefreshSessionStore.key(1L))));

        // 유예 밖 직전 토큰 → 401(재사용) + 키 삭제, 이후 최신 토큰도 401
        BusinessException reused = assertThrows(BusinessException.class,
                () -> service(tokens, users, encoder, later + GRACE + 1).refresh(new RefreshRequest(login.refreshToken())));
        assertEquals(ErrorCode.REFRESH_TOKEN_REUSED, reused.getErrorCode());
        assertNull(value(1L));
        BusinessException gone = assertThrows(BusinessException.class,
                () -> service(tokens, users, encoder, later + GRACE + 2).refresh(new RefreshRequest(rotated.refreshToken())));
        assertEquals(ErrorCode.REFRESH_TOKEN_NOT_FOUND, gone.getErrorCode());
    }

    private AuthService service(JwtTokenProvider tokens, UserRepository users, BCryptPasswordEncoder encoder, long at) {
        return new AuthService(users, tokens, redis, encoder, store, Clock.fixed(Instant.ofEpochMilli(at), ZoneOffset.UTC));
    }

    private static String i(long at) {
        return Long.toString(at);
    }

    private String value(long userId) {
        return redis.opsForValue().get(RefreshSessionStore.key(userId));
    }

    private long pttl(long userId) {
        Long ttl = redis.getExpire(RefreshSessionStore.key(userId), TimeUnit.MILLISECONDS);
        return ttl == null ? -3 : ttl;
    }
}
