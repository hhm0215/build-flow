package com.buildflow.notification.global.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class OutboxPublisherTest {

    @Test
    void brokerAckMarksSent() {
        OutboxClaimService claims = mock(OutboxClaimService.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        OutboxClaim claim = new OutboxClaim("event-1", "warranty.expiring", "7", "{}", "token");
        when(claims.claimNext()).thenReturn(OutboxClaimAttempt.claimed(claim), OutboxClaimAttempt.empty());
        when(kafka.send(claim.topic(), claim.recordKey(), claim.payloadJson()))
                .thenReturn(CompletableFuture.completedFuture(null));

        new OutboxPublisher(claims, kafka).publishReady();

        verify(claims).markSent(claim);
    }

    @Test
    void brokerFailureRetainsRetryableClaim() {
        OutboxClaimService claims = mock(OutboxClaimService.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        OutboxClaim claim = new OutboxClaim("event-2", "warranty.expiring", "7", "{}", "token");
        when(claims.claimNext()).thenReturn(OutboxClaimAttempt.claimed(claim), OutboxClaimAttempt.empty());
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafka.send(claim.topic(), claim.recordKey(), claim.payloadJson())).thenReturn(failed);

        new OutboxPublisher(claims, kafka).publishReady();

        verify(claims).markFailed(org.mockito.ArgumentMatchers.eq(claim),
                org.mockito.ArgumentMatchers.contains("broker unavailable"));
    }

    @Test
    void retiredLegacyRowDoesNotStarveNextValidClaim() {
        OutboxClaimService claims = mock(OutboxClaimService.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        OutboxClaim valid = new OutboxClaim("event-3", "warranty.expiring", "8", "{}", "token");
        when(claims.claimNext()).thenReturn(OutboxClaimAttempt.suppressed(),
                OutboxClaimAttempt.claimed(valid), OutboxClaimAttempt.empty());
        when(kafka.send(valid.topic(), valid.recordKey(), valid.payloadJson()))
                .thenReturn(CompletableFuture.completedFuture(null));

        new OutboxPublisher(claims, kafka).publishReady();

        verify(kafka).send(valid.topic(), valid.recordKey(), valid.payloadJson());
        verify(claims).markSent(valid);
        verify(claims, times(3)).claimNext();
    }

    @Test
    void onePollRetiresAtMostOneHundredRowsAndResumesNextPoll() {
        OutboxClaimService claims = mock(OutboxClaimService.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        OutboxClaim valid = new OutboxClaim("event-4", "warranty.expiring", "8", "{}", "token");
        AtomicInteger calls = new AtomicInteger();
        when(claims.claimNext()).thenAnswer(ignored -> {
            int call = calls.incrementAndGet();
            if (call <= 100) return OutboxClaimAttempt.suppressed();
            if (call == 101) return OutboxClaimAttempt.claimed(valid);
            return OutboxClaimAttempt.empty();
        });
        when(kafka.send(valid.topic(), valid.recordKey(), valid.payloadJson()))
                .thenReturn(CompletableFuture.completedFuture(null));
        OutboxPublisher publisher = new OutboxPublisher(claims, kafka);

        publisher.publishReady();
        verifyNoInteractions(kafka);
        publisher.publishReady();

        verify(kafka).send(valid.topic(), valid.recordKey(), valid.payloadJson());
        verify(claims).markSent(valid);
        verify(claims, times(102)).claimNext();
    }
}
