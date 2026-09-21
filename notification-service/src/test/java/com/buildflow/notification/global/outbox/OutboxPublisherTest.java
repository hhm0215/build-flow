package com.buildflow.notification.global.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class OutboxPublisherTest {

    @Test
    void brokerAckMarksSent() {
        OutboxClaimService claims = mock(OutboxClaimService.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        OutboxClaim claim = new OutboxClaim("event-1", "warranty.expiring", "7", "{}", "token");
        when(claims.claimNext()).thenReturn(Optional.of(claim), Optional.empty());
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
        when(claims.claimNext()).thenReturn(Optional.of(claim), Optional.empty());
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafka.send(claim.topic(), claim.recordKey(), claim.payloadJson())).thenReturn(failed);

        new OutboxPublisher(claims, kafka).publishReady();

        verify(claims).markFailed(org.mockito.ArgumentMatchers.eq(claim),
                org.mockito.ArgumentMatchers.contains("broker unavailable"));
    }
}
