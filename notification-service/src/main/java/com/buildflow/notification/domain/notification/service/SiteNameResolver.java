package com.buildflow.notification.domain.notification.service;

import com.buildflow.notification.infra.feign.SiteReferenceClient;
import com.buildflow.notification.infra.feign.SiteReferenceResponse;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 알림 문구에 넣을 현장명을 site-service에서 조회한다.
 * 현장명은 보조 정보라 조회 실패(장애·404·응답 이상)는 null로 폴백하고 알림 저장을 막지 않는다.
 * 트랜잭션·행 잠금 밖(Kafka 소비자 스레드)에서만 호출한다.
 *
 * <p>site-service 장애 중 밀린 이벤트를 소비할 때 건마다 Feign 타임아웃(최대 연결 2초 + 읽기 3초)을 기다리면
 * 한 poll 묶음이 {@code max.poll.interval.ms}를 넘어 리밸런스가 반복될 수 있다. 그래서 장애(404 제외) 뒤
 * {@link #FAILURE_BACKOFF_SECONDS}초 동안은 조회를 건너뛰고 바로 폴백한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SiteNameResolver {

    static final long FAILURE_BACKOFF_SECONDS = 30;

    private final SiteReferenceClient siteReferenceClient;

    private LongSupplier nanoClock = System::nanoTime;
    private volatile long skipUntilNanos;
    private volatile boolean backingOff;

    public String resolve(Long siteId) {
        if (siteId == null || siteId <= 0) {
            return null;
        }
        if (backingOff && nanoClock.getAsLong() - skipUntilNanos < 0) {
            return null;
        }
        try {
            SiteReferenceResponse response = siteReferenceClient.findById(siteId);
            backingOff = false;
            if (response == null || !response.success() || response.data() == null
                    || !siteId.equals(response.data().id())) {
                return null;
            }
            return response.data().siteName();
        } catch (FeignException.NotFound e) {
            backingOff = false;
            return null;
        } catch (RuntimeException e) {
            skipUntilNanos = nanoClock.getAsLong() + TimeUnit.SECONDS.toNanos(FAILURE_BACKOFF_SECONDS);
            backingOff = true;
            log.warn("알림 현장명 조회 실패, {}초간 ID 문구로 폴백: siteId={}, cause={}",
                    FAILURE_BACKOFF_SECONDS, siteId, e.getClass().getSimpleName());
            return null;
        }
    }

    /** 테스트 전용 시계 주입. */
    void setNanoClock(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }
}
