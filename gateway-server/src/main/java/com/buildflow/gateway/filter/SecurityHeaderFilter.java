package com.buildflow.gateway.filter;

import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

@Configuration
public class SecurityHeaderFilter {

    @Bean
    public GlobalFilter securityHeadersFilter() {
        return (exchange, chain) -> chain.filter(exchange)
                .then(Mono.fromRunnable(() -> {
                    HttpHeaders headers = exchange.getResponse().getHeaders();
                    headers.add("X-Content-Type-Options", "nosniff");
                    headers.add("X-Frame-Options", "DENY");
                    headers.add("X-XSS-Protection", "1; mode=block");
                    headers.add("Referrer-Policy", "strict-origin-when-cross-origin");
                }));
    }
}
