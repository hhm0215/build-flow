package com.buildflow.purchase.global.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class OutboxDispatcherTest {

    private final OutboxClaimService claimService = mock(OutboxClaimService.class);
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final OutboxDispatcher dispatcher = new OutboxDispatcher(claimService, kafkaTemplate);
    private final ClaimedOutboxEvent event = new ClaimedOutboxEvent(
            "11111111-1111-4111-8111-111111111111", "purchase.registered", "7",
            "{\"eventId\":\"11111111-1111-4111-8111-111111111111\"}",
            "22222222-2222-4222-8222-222222222222", 1);

    @Test
    void ackMarksOnlyMatchingClaimSent() throws Exception {
        when(claimService.claimNext()).thenReturn(Optional.of(event));
        when(kafkaTemplate.send(event.topic(), event.recordKey(), event.payloadJson()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        when(claimService.markSent(event.eventId(), event.claimToken())).thenReturn(true);

        assertTrue(dispatcher.dispatchOnce());

        verify(kafkaTemplate).send(event.topic(), event.recordKey(), event.payloadJson());
        verify(claimService).markSent(event.eventId(), event.claimToken());
        verify(claimService, never()).releaseForRetry(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void brokerFailureKeepsOriginalEventForRetry() {
        when(claimService.claimNext()).thenReturn(Optional.of(event));
        when(kafkaTemplate.send(event.topic(), event.recordKey(), event.payloadJson()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertTrue(dispatcher.dispatchOnce());

        verify(claimService).releaseForRetry(eq(event.eventId()), eq(event.claimToken()),
                eq(1), contains("broker down"));
        verify(claimService, never()).markSent(anyString(), anyString());
    }

    @Test
    void ackThenStatusWriteFailureReleasesSameEventForDuplicateSafeRetry() {
        when(claimService.claimNext()).thenReturn(Optional.of(event));
        when(kafkaTemplate.send(event.topic(), event.recordKey(), event.payloadJson()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        when(claimService.markSent(event.eventId(), event.claimToken()))
                .thenThrow(new IllegalStateException("DB unavailable"));

        assertTrue(dispatcher.dispatchOnce());

        verify(claimService).releaseForRetry(eq(event.eventId()), eq(event.claimToken()),
                eq(1), contains("DB unavailable"));
        verify(kafkaTemplate).send(event.topic(), event.recordKey(), event.payloadJson());
    }

    @Test
    void interruptionStopsBatchWithoutClaimingAnotherEvent() throws Exception {
        when(claimService.claimNext()).thenReturn(Optional.of(event));
        CompletableFuture<SendResult<String, String>> interrupted = mock(CompletableFuture.class);
        when(kafkaTemplate.send(event.topic(), event.recordKey(), event.payloadJson()))
                .thenReturn(interrupted);
        when(interrupted.get(5, java.util.concurrent.TimeUnit.SECONDS))
                .thenThrow(new InterruptedException("shutdown"));

        try {
            assertFalse(dispatcher.dispatchOnce());
            verify(claimService).claimNext();
            verify(claimService, never()).releaseForRetry(anyString(), anyString(), anyInt(), anyString());
        } finally {
            Thread.interrupted();
        }
    }
}
