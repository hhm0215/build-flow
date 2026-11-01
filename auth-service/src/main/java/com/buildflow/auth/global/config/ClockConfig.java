package com.buildflow.auth.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /** 세션 절대 상한·재사용 유예 계산 기준 시각. 테스트는 고정 Clock을 주입한다. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
