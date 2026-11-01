package com.buildflow.auth.domain.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 사용자당 refresh 세션 1개를 Redis 키 {@code auth:refresh:{userId}}에 둔다(키 형식은 기존과 같다).
 *
 * <p>값은 {@code v3|sid|현재 토큰 SHA-256|직전 토큰 SHA-256|회전 시각(ms)} — 원문 토큰은 저장하지 않는다.
 * 이전 형식(원문 JWT) 값은 회전 대상이 아니며 다음 로그인이 덮어쓰고 로그아웃 DEL로 지워진다.
 *
 * <p>회전은 Lua 한 번으로 GET→비교→SET을 원자 처리한다. Redis는 스크립트를 단일 스레드로 실행하므로
 * 로그아웃 DEL과 경합해도 "로그아웃 뒤 새 refresh가 살아나는" 순서가 생기지 않는다(DEL이 먼저면 GET이 nil,
 * 회전이 먼저면 DEL이 새 값을 지운다).
 */
@Component
@RequiredArgsConstructor
public class RefreshSessionStore {

    static final String KEY_PREFIX = "auth:refresh:";
    static final String VALUE_VERSION = "v3";

    /**
     * KEYS[1]=refresh 키, ARGV[1]=제시 토큰 해시, ARGV[2]=제시 토큰 sid, ARGV[3]=새 값, ARGV[4]=TTL(ms),
     * ARGV[5]=현재 시각(ms), ARGV[6]=동시 회전 유예(ms).
     * 반환: 1 회전, 0 키 없음(로그아웃·만료), 2 재사용 탐지(키 폐기), 3 다른 세션/이전 형식(변경 없음),
     * 4 직전 토큰의 유예 내 동시 회전(변경 없음).
     */
    static final String ROTATE_SCRIPT = String.join("\n",
            "local current = redis.call('GET', KEYS[1])",
            "if not current then return 0 end",
            "local sid, currentHash, previousHash, rotatedAt = string.match(current, '^v3|([^|]+)|([^|]+)|([^|]*)|(%d*)$')",
            "if not sid or sid ~= ARGV[2] then return 3 end",
            "if currentHash == ARGV[1] then",
            "  redis.call('SET', KEYS[1], ARGV[3], 'PX', ARGV[4])",
            "  return 1",
            "end",
            "if previousHash == ARGV[1] and rotatedAt ~= '' and tonumber(ARGV[5]) - tonumber(rotatedAt) <= tonumber(ARGV[6]) then",
            "  return 4",
            "end",
            "redis.call('DEL', KEYS[1])",
            "return 2");

    private static final RedisScript<Long> ROTATE = new DefaultRedisScript<>(ROTATE_SCRIPT, Long.class);

    private final StringRedisTemplate redisTemplate;

    public enum RotationResult { ROTATED, NOT_FOUND, REUSED, OTHER_SESSION, CONCURRENT }

    public void start(Long userId, String sessionId, String refreshToken, long ttlMillis) {
        redisTemplate.opsForValue().set(key(userId), encode(sessionId, hash(refreshToken), "", ""),
                ttlMillis, TimeUnit.MILLISECONDS);
    }

    public RotationResult rotate(Long userId, String sessionId, String presentedToken, String newToken,
                                 long ttlMillis, long nowMillis, long graceMillis) {
        String presentedHash = hash(presentedToken);
        String newValue = encode(sessionId, hash(newToken), presentedHash, String.valueOf(nowMillis));
        Long code = redisTemplate.execute(ROTATE, List.of(key(userId)),
                presentedHash, sessionId, newValue, String.valueOf(ttlMillis),
                String.valueOf(nowMillis), String.valueOf(graceMillis));
        return toResult(code);
    }

    public void revoke(Long userId) {
        redisTemplate.delete(key(userId));
    }

    static RotationResult toResult(Long code) {
        if (code == null) {
            throw new IllegalStateException("refresh rotation script returned no result");
        }
        return switch (code.intValue()) {
            case 1 -> RotationResult.ROTATED;
            case 0 -> RotationResult.NOT_FOUND;
            case 2 -> RotationResult.REUSED;
            case 3 -> RotationResult.OTHER_SESSION;
            case 4 -> RotationResult.CONCURRENT;
            default -> throw new IllegalStateException("unexpected refresh rotation result: " + code);
        };
    }

    static String key(Long userId) {
        return KEY_PREFIX + userId;
    }

    static String encode(String sessionId, String currentHash, String previousHash, String rotatedAt) {
        return String.join("|", VALUE_VERSION, sessionId, currentHash, previousHash, rotatedAt);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
