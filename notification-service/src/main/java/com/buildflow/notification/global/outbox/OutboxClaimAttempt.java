package com.buildflow.notification.global.outbox;

/** One short transaction either claims a row, retires an invalid row, or finds no work. */
public record OutboxClaimAttempt(OutboxClaim claim, boolean retired) {

    public static OutboxClaimAttempt claimed(OutboxClaim claim) {
        return new OutboxClaimAttempt(claim, false);
    }

    public static OutboxClaimAttempt suppressed() {
        return new OutboxClaimAttempt(null, true);
    }

    public static OutboxClaimAttempt empty() {
        return new OutboxClaimAttempt(null, false);
    }
}
