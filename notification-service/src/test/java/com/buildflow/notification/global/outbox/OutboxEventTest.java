package com.buildflow.notification.global.outbox;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxEventTest {

    @Test
    void retryKeepsOriginalPayloadAndRejectsStaleClaim() {
        Instant now = Instant.parse("2026-09-20T00:00:00Z");
        OutboxEvent event = new OutboxEvent("event-1", "warranty.expiring", "7", "{\"eventId\":\"event-1\"}", now);
        event.claim("first", now.plusSeconds(60));
        assertTrue(event.markFailed("first", now, "broker unavailable"));
        assertEquals(now.plusSeconds(1), event.getNextAttemptAt());
        assertEquals("{\"eventId\":\"event-1\"}", event.getPayloadJson());

        event.claim("second", now.plusSeconds(61));
        assertFalse(event.markSent("first", now));
        assertFalse(event.markFailed("first", now, "late failure"));
        assertTrue(event.markFailed("second", now, "broker unavailable"));
        assertEquals(now.plusSeconds(2), event.getNextAttemptAt());
        assertEquals(OutboxStatus.PENDING, event.getStatus());

        event.claim("third", now.plusSeconds(62));
        assertTrue(event.markSent("third", now));
        assertEquals(OutboxStatus.SENT, event.getStatus());
        assertNull(event.getClaimToken());
        assertEquals("{\"eventId\":\"event-1\"}", event.getPayloadJson());
    }

    @Test
    void suppressionRetainsOriginalEnvelopeAndNeverClaimsBrokerAck() {
        Instant now = Instant.parse("2026-10-15T00:00:00Z");
        OutboxEvent event = new OutboxEvent("event-2", "warranty.expiring", "7", "{}", now);
        event.claim("expired-token", now.minusSeconds(1));

        event.suppress(now, "WARRANTY_UNCONFIRMED");

        assertEquals(OutboxStatus.SUPPRESSED, event.getStatus());
        assertEquals(now, event.getSuppressedAt());
        assertEquals("WARRANTY_UNCONFIRMED", event.getLastError());
        assertNull(event.getClaimToken());
        assertNull(event.getLeaseUntil());
        assertNull(event.getSentAt());
        assertEquals("{}", event.getPayloadJson());
        assertFalse(event.markSent("expired-token", now));
    }
}
