package com.buildflow.notification.global.outbox;

import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.entity.OcrStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.UUID;

/** Fail-closed check before an old warranty alert leaves the durable outbox. */
final class WarrantyOutboxEligibility {

    private WarrantyOutboxEligibility() {
    }

    /** A null result is eligible; stable codes are stored without the sensitive payload. */
    static String rejectionReason(OutboxEvent event, DefectWarranty warranty,
                                  LocalDate today, int thresholdDays, ObjectMapper mapper) {
        final Snapshot snapshot;
        try {
            snapshot = parse(event, mapper);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return "MALFORMED_WARRANTY_EVENT";
        }
        if (warranty == null) return "WARRANTY_REMOVED";
        if (warranty.getOcrStatus() != OcrStatus.MANUAL
                || warranty.getExpiryConfirmedAt() == null) return "WARRANTY_UNCONFIRMED";
        if (!snapshot.confirmationRef.equals(warranty.getExpiryConfirmedAt().toString())
                || event.getCreatedAt().isBefore(warranty.getExpiryConfirmedAt())) {
            return "WARRANTY_CONFIRMATION_CHANGED";
        }
        if (warranty.getInsuranceCompany() == null || warranty.getInsuranceCompany().isBlank()
                || warranty.getStartDate() == null || warranty.getEndDate() == null
                || warranty.getStartDate().isAfter(warranty.getEndDate())
                || !Objects.equals(snapshot.siteId, warranty.getSiteId())
                || !Objects.equals(snapshot.insuranceCompany, warranty.getInsuranceCompany())
                || !Objects.equals(snapshot.endDate, warranty.getEndDate())) {
            return "WARRANTY_FACTS_CHANGED";
        }
        if (warranty.getEndDate().isBefore(today)
                || warranty.getEndDate().isAfter(today.plusDays(thresholdDays))) {
            return "WARRANTY_OUTSIDE_ALERT_WINDOW";
        }
        return null;
    }

    private static Snapshot parse(OutboxEvent event, ObjectMapper mapper) throws JsonProcessingException {
        JsonNode root = mapper.readTree(event.getPayloadJson());
        if (root == null || !root.isObject()
                || !canonicalEventId(event.getEventId())
                || !event.getEventId().equals(text(root, "eventId"))
                || !"WARRANTY_EXPIRING".equals(text(root, "eventType"))) {
            throw new IllegalArgumentException("Invalid warranty envelope");
        }
        JsonNode payload = root.path("payload");
        if (!payload.isObject()) throw new IllegalArgumentException("Missing warranty payload");
        long warrantyId = positiveLong(payload, "warrantyId");
        if (!Long.toString(warrantyId).equals(event.getRecordKey())) {
            throw new IllegalArgumentException("Warranty record key mismatch");
        }
        long daysUntilExpiry = nonNegativeLong(payload, "daysUntilExpiry");
        if (daysUntilExpiry > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid expiry interval");
        }
        try {
            return new Snapshot(positiveLong(payload, "siteId"), text(payload, "insuranceCompany"),
                    LocalDate.parse(text(payload, "endDate")), text(payload, "expiryConfirmationRef"));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid warranty end date", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing text field");
        }
        return value.asText();
    }

    private static boolean canonicalEventId(String eventId) {
        try {
            return UUID.fromString(eventId).toString().equals(eventId);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static long positiveLong(JsonNode node, String field) {
        long value = nonNegativeLong(node, field);
        if (value == 0) throw new IllegalArgumentException("Nonpositive ID");
        return value;
    }

    private static long nonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 0) {
            throw new IllegalArgumentException("Invalid integer field");
        }
        return value.longValue();
    }

    private record Snapshot(Long siteId, String insuranceCompany,
                            LocalDate endDate, String confirmationRef) {
    }
}
