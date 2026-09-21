package com.buildflow.purchase.global.outbox;

public record ClaimedOutboxEvent(
        String eventId,
        String topic,
        String recordKey,
        String payloadJson,
        String claimToken,
        int attempts
) {
}
