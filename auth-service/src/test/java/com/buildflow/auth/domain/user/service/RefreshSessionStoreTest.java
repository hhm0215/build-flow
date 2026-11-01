package com.buildflow.auth.domain.user.service;

import com.buildflow.auth.domain.user.service.RefreshSessionStore.RotationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshSessionStoreTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;

    @Test
    void startStoresHashedTokenUnderExistingKeyFormat() {
        when(redis.opsForValue()).thenReturn(values);

        new RefreshSessionStore(redis).start(1L, "sid-1", "raw-refresh", 5000L);

        String expected = "v3|sid-1|" + RefreshSessionStore.hash("raw-refresh") + "||";
        verify(values).set("auth:refresh:1", expected, 5000L, TimeUnit.MILLISECONDS);
        assertFalse(expected.contains("raw-refresh"), "원문 토큰은 Redis에 남기지 않는다");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rotatePassesAtomicScriptArgumentsAndNewValueKeepsPreviousHash() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        RotationResult result = new RefreshSessionStore(redis)
                .rotate(1L, "sid-1", "old", "new", 4000L, 100L, 30L);

        assertEquals(RotationResult.ROTATED, result);
        ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(redis).execute(script.capture(), keys.capture(), args.capture(), args.capture(), args.capture(),
                args.capture(), args.capture(), args.capture());
        assertEquals(RefreshSessionStore.ROTATE_SCRIPT, script.getValue().getScriptAsString());
        assertEquals(Long.class, script.getValue().getResultType());
        assertEquals(List.of("auth:refresh:1"), keys.getValue());
        String oldHash = RefreshSessionStore.hash("old");
        assertEquals(List.of(oldHash, "sid-1",
                        "v3|sid-1|" + RefreshSessionStore.hash("new") + "|" + oldHash + "|100",
                        "4000", "100", "30"),
                args.getAllValues());
    }

    @Test
    void mapsEveryScriptReturnCode() {
        assertEquals(RotationResult.ROTATED, RefreshSessionStore.toResult(1L));
        assertEquals(RotationResult.NOT_FOUND, RefreshSessionStore.toResult(0L));
        assertEquals(RotationResult.REUSED, RefreshSessionStore.toResult(2L));
        assertEquals(RotationResult.OTHER_SESSION, RefreshSessionStore.toResult(3L));
        assertEquals(RotationResult.CONCURRENT, RefreshSessionStore.toResult(4L));
        assertThrows(IllegalStateException.class, () -> RefreshSessionStore.toResult(9L));
        assertThrows(IllegalStateException.class, () -> RefreshSessionStore.toResult(null));
    }

    @Test
    void scriptComparesBeforeSetAndRevokesOnlyOnReuse() {
        String script = RefreshSessionStore.ROTATE_SCRIPT;
        int get = script.indexOf("redis.call('GET', KEYS[1])");
        int compare = script.indexOf("if currentHash == ARGV[1] then");
        int set = script.indexOf("redis.call('SET', KEYS[1], ARGV[3], 'PX', ARGV[4])");
        int grace = script.indexOf("previousHash == ARGV[1]");
        int del = script.indexOf("redis.call('DEL', KEYS[1])");
        assertTrue(get >= 0 && get < compare && compare < set && set < grace && grace < del,
                "GET → 현재 해시 비교 → SET, 직전 토큰 유예 판정 → 그 외 재사용 DEL 순서");
        // 키가 없거나(로그아웃) 다른 세션·이전 형식이면 SET/DEL 전에 반환한다.
        assertTrue(script.indexOf("if not current then return 0 end") < compare);
        assertTrue(script.indexOf("if not sid or sid ~= ARGV[2] then return 3 end") < compare);
        assertEquals(1, script.split("redis.call\\('SET'", -1).length - 1, "SET은 정상 회전 한 곳뿐");
        assertEquals(1, script.split("redis.call\\('DEL'", -1).length - 1, "DEL은 재사용 탐지 한 곳뿐");
    }

    @Test
    void revokeDeletesTheUserKey() {
        new RefreshSessionStore(redis).revoke(1L);

        verify(redis).delete("auth:refresh:1");
    }

    @Test
    void hashIsStableHex() {
        String hash = RefreshSessionStore.hash("token");
        assertEquals(64, hash.length());
        assertEquals(hash, RefreshSessionStore.hash("token"));
        assertTrue(hash.matches("[0-9a-f]+"));
        assertFalse(RefreshSessionStore.encode("s", hash, "", "").contains("token"));
        assertFalse(hash.equals(RefreshSessionStore.hash("token2")));
    }
}
