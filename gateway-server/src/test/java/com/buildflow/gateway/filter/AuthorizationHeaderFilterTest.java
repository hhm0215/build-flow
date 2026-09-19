package com.buildflow.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthorizationHeaderFilterTest {

    private static final String SECRET = "gateway-filter-test-secret-must-be-at-least-32-bytes";

    @Test
    void acceptsCurrentAdminAccessTokenClaims() {
        assertTrue(AuthorizationHeaderFilter.isAuthorizedClaims(claims("access", "ADMIN", 2)));
    }

    @Test
    void rejectsRefreshViewerAndLegacyClaims() {
        assertFalse(AuthorizationHeaderFilter.isAuthorizedClaims(claims("refresh", "ADMIN", 2)));
        assertFalse(AuthorizationHeaderFilter.isAuthorizedClaims(claims("access", "VIEWER", 2)));
        assertFalse(AuthorizationHeaderFilter.isAuthorizedClaims(claims("access", "ADMIN", 1)));
        assertFalse(AuthorizationHeaderFilter.isAuthorizedClaims(
                Jwts.claims().subject("1").add("type", "access").add("role", "ADMIN").build()
        ));
    }

    @Test
    void replacesUntrustedUserHeadersWithSignedClaims() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.hasKey(anyString())).thenReturn(Mono.just(false));
        AuthorizationHeaderFilter filter = new AuthorizationHeaderFilter(redis);
        ReflectionTestUtils.setField(filter, "jwtSecret", SECRET);

        String token = Jwts.builder()
                .subject("42")
                .claim("type", "access")
                .claim("role", "ADMIN")
                .claim("authVersion", 2)
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/sites")
                .header("Authorization", "Bearer " + token)
                .header("X-User-Id", "999")
                .header("X-User-Role", "VIEWER"));
        GatewayFilterChain chain = forwarded -> {
            assertEquals("42", forwarded.getRequest().getHeaders().getFirst("X-User-Id"));
            assertEquals("ADMIN", forwarded.getRequest().getHeaders().getFirst("X-User-Role"));
            assertEquals(1, forwarded.getRequest().getHeaders().get("X-User-Id").size());
            return Mono.empty();
        };

        filter.apply(new AuthorizationHeaderFilter.Config()).filter(exchange, chain).block();
    }

    @Test
    void rejectsLegacyAdminAccessToken() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        AuthorizationHeaderFilter filter = new AuthorizationHeaderFilter(redis);
        ReflectionTestUtils.setField(filter, "jwtSecret", SECRET);

        String oldToken = Jwts.builder()
                .subject("42")
                .claim("type", "access")
                .claim("role", "ADMIN")
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/sites")
                .header("Authorization", "Bearer " + oldToken));
        GatewayFilterChain chain = forwarded -> Mono.error(new AssertionError("Legacy token was forwarded"));

        filter.apply(new AuthorizationHeaderFilter.Config()).filter(exchange, chain).block();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    private Claims claims(String type, String role, int authVersion) {
        return Jwts.claims()
                .subject("1")
                .add("type", type)
                .add("role", role)
                .add("authVersion", authVersion)
                .build();
    }
}
