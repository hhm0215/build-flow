package com.buildflow.notification.domain.notification.service;

import com.buildflow.notification.infra.feign.SiteReferenceClient;
import com.buildflow.notification.infra.feign.SiteReferenceResponse;
import feign.FeignException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SiteNameResolverTest {

    private final SiteReferenceClient client = mock(SiteReferenceClient.class);
    private final SiteNameResolver resolver = new SiteNameResolver(client);
    private final AtomicLong now = new AtomicLong(1_000L);

    SiteNameResolverTest() {
        resolver.setNanoClock(now::get);
    }

    @Test
    void returnsSiteNameWhenSiteResponds() {
        when(client.findById(7L)).thenReturn(ok(7L, "강남 A현장"));

        assertThat(resolver.resolve(7L)).isEqualTo("강남 A현장");
    }

    @Test
    void abnormalResponsesAndNotFoundFallBackWithoutBackoff() {
        when(client.findById(9L)).thenReturn(ok(10L, "다른 현장"));
        when(client.findById(11L)).thenReturn(new SiteReferenceResponse(false, null));
        when(client.findById(12L)).thenThrow(mock(FeignException.NotFound.class));
        when(client.findById(7L)).thenReturn(ok(7L, "강남 A현장"));

        assertThat(resolver.resolve(9L)).isNull();
        assertThat(resolver.resolve(11L)).isNull();
        assertThat(resolver.resolve(12L)).isNull();
        assertThat(resolver.resolve(null)).isNull();
        assertThat(resolver.resolve(0L)).isNull();
        // 404는 장애가 아니므로 다음 조회를 막지 않는다
        assertThat(resolver.resolve(7L)).isEqualTo("강남 A현장");
        verify(client, never()).findById(0L);
    }

    @Test
    void outageFallsBackAndSkipsLookupsDuringBackoff() {
        when(client.findById(7L))
                .thenThrow(mock(FeignException.ServiceUnavailable.class))
                .thenReturn(ok(7L, "강남 A현장"));
        when(client.findById(8L)).thenThrow(new IllegalStateException("decode"));

        assertThat(resolver.resolve(7L)).isNull();
        // 백오프 중에는 Feign을 부르지 않고 바로 폴백
        assertThat(resolver.resolve(8L)).isNull();
        assertThat(resolver.resolve(7L)).isNull();
        verify(client, times(1)).findById(7L);
        verify(client, never()).findById(8L);

        now.addAndGet(TimeUnit.SECONDS.toNanos(SiteNameResolver.FAILURE_BACKOFF_SECONDS));
        assertThat(resolver.resolve(7L)).isEqualTo("강남 A현장");
    }

    private SiteReferenceResponse ok(Long id, String name) {
        return new SiteReferenceResponse(true, new SiteReferenceResponse.SiteIdentity(id, null, name));
    }
}
