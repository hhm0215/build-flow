package com.buildflow.tax.global.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class OutboxDispatcherTest {

    private final OutboxClaimService claimService = mock(OutboxClaimService.class);
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final OutboxDispatcher dispatcher = new OutboxDispatcher(claimService, kafkaTemplate);
    private final OutboxClaim claim = new OutboxClaim("11111111-1111-4111-8111-111111111111",
            "tax.registered", "7", "{\"eventId\":\"same-id\"}", "token", 1);

    @Test
    void brokerAckMarksClaimSent() {
        when(claimService.claimNext(any(LocalDateTime.class)))
                .thenReturn(Optional.of(claim), Optional.empty());
        when(kafkaTemplate.send(claim.topic(), claim.recordKey(), claim.payloadJson()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        when(claimService.markSent(org.mockito.ArgumentMatchers.eq(claim), any(LocalDateTime.class)))
                .thenReturn(true);

        dispatcher.dispatch();

        verify(kafkaTemplate).send("tax.registered", "7", claim.payloadJson());
        verify(claimService).markSent(org.mockito.ArgumentMatchers.eq(claim), any(LocalDateTime.class));
    }

    @Test
    void brokerFailureKeepsSameClaimForBackoff() {
        when(claimService.claimNext(any(LocalDateTime.class)))
                .thenReturn(Optional.of(claim), Optional.empty());
        when(kafkaTemplate.send(claim.topic(), claim.recordKey(), claim.payloadJson()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        dispatcher.dispatch();

        verify(claimService).markFailed(org.mockito.ArgumentMatchers.eq(claim),
                any(LocalDateTime.class), any(Exception.class));
        assertThat(claim.payloadJson()).contains("same-id");
    }
}
