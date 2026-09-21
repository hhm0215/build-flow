package com.buildflow.tax.global.outbox;

public record OutboxClaim(String eventId, String topic, String recordKey,
                          String payloadJson, String claimToken, int attempts) {
}
