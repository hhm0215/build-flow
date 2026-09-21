package com.buildflow.notification.global.outbox;

public record OutboxClaim(String eventId, String topic, String recordKey,
                          String payloadJson, String claimToken) {
}
