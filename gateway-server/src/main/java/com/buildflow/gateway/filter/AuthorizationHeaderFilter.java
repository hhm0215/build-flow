package com.buildflow.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class AuthorizationHeaderFilter extends AbstractGatewayFilterFactory<AuthorizationHeaderFilter.Config> {

    private static final String BLACKLIST_PREFIX = "auth:blacklist:";

    private final ReactiveStringRedisTemplate redisTemplate;

    @Value("${jwt.secret}")
    private String jwtSecret;

    public AuthorizationHeaderFilter(ReactiveStringRedisTemplate redisTemplate) {
        super(Config.class);
        this.redisTemplate = redisTemplate;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return onError(exchange, HttpStatus.UNAUTHORIZED);
            }

            String token = authHeader.substring(7);
            Claims claims;
            try {
                claims = getClaims(token);
            } catch (Exception e) {
                log.warn("JWT token validation failed");
                return onError(exchange, HttpStatus.UNAUTHORIZED);
            }
            if (!isAuthorizedClaims(claims)) {
                return onError(exchange, HttpStatus.UNAUTHORIZED);
            }

            return redisTemplate.hasKey(BLACKLIST_PREFIX + token)
                    .flatMap(isBlacklisted -> {
                        if (Boolean.TRUE.equals(isBlacklisted)) {
                            log.debug("Blacklisted token rejected");
                            return onError(exchange, HttpStatus.UNAUTHORIZED);
                        }

                        ServerWebExchange modifiedExchange = exchange.mutate()
                                .request(r -> r.headers(headers -> {
                                    headers.set("X-User-Id", claims.getSubject());
                                    headers.set("X-User-Role", "ADMIN");
                                }))
                                .build();

                        return chain.filter(modifiedExchange);
                    });
        };
    }

    static boolean isAuthorizedClaims(Claims claims) {
        Object version = claims.get("authVersion");
        return "access".equals(claims.get("type", String.class))
                && "ADMIN".equals(claims.get("role", String.class))
                && version instanceof Number
                && ((Number) version).intValue() == 2
                && claims.getSubject() != null
                && !claims.getSubject().isBlank();
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private Mono<Void> onError(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }

    public static class Config {}
}
