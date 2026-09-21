package com.buildflow.estimate.global.outbox;

public record OutboxClaim(String eventId, String topic, String recordKey,
                          String payloadJson, String token, int attempts) {
}
