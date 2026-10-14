package com.buildflow.notification.domain.warranty.scheduler;

import com.buildflow.notification.domain.warranty.WarrantyDatePolicy;
import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.entity.OcrStatus;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import com.buildflow.notification.global.config.JpaAuditingConfig;
import com.buildflow.notification.global.kafka.KafkaProducerService;
import com.buildflow.notification.global.outbox.OutboxClaimService;
import com.buildflow.notification.global.outbox.OutboxEvent;
import com.buildflow.notification.global.outbox.OutboxEventRepository;
import com.buildflow.notification.global.outbox.OutboxEventService;
import com.buildflow.notification.global.outbox.OutboxStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@ActiveProfiles("test")
@Import({WarrantyAlertService.class, KafkaProducerService.class, OutboxEventService.class,
        OutboxClaimService.class, JpaAuditingConfig.class, WarrantyAlertOutboxJpaTest.JacksonConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WarrantyAlertOutboxJpaTest {

    @Autowired private WarrantyAlertService alerts;
    @Autowired private DefectWarrantyRepository warranties;
    @Autowired private OutboxEventRepository outbox;
    @Autowired private OutboxClaimService claims;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        outbox.deleteAll();
        warranties.deleteAll();
    }

    @Test
    void cooldownAndOutboxCommitTogetherWithoutDuplicate() {
        LocalDate today = WarrantyDatePolicy.today();
        DefectWarranty warranty = warranties.save(DefectWarranty.builder()
                .siteId(7L).insuranceCompany("보험사")
                .startDate(today.minusDays(1)).endDate(today.plusDays(5)).build());

        assertTrue(alerts.enqueueIfEligible(warranty.getId(), today,
                today.plusDays(30), today.minusDays(7)));
        assertFalse(alerts.enqueueIfEligible(warranty.getId(), today,
                today.plusDays(30), today.minusDays(7)));

        assertEquals(today, warranties.findById(warranty.getId()).orElseThrow().getLastExpiringAlertSentAt());
        assertEquals(1, outbox.count());
        OutboxEvent event = outbox.findAll().get(0);
        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals("warranty.expiring", event.getTopic());
        assertEquals(warranty.getId().toString(), event.getRecordKey());
        assertTrue(event.getPayloadJson().contains("WARRANTY_EXPIRING"));
        assertTrue(event.getPayloadJson().contains(event.getEventId()));
    }

    @Test
    void leaseRecoveryUsesIdenticalEnvelopeAndRejectsStaleToken() {
        LocalDate today = WarrantyDatePolicy.today();
        DefectWarranty warranty = warranties.save(DefectWarranty.builder()
                .siteId(8L).insuranceCompany("보험사")
                .startDate(today.minusDays(1)).endDate(today.plusDays(2)).build());
        assertTrue(alerts.enqueueIfEligible(warranty.getId(), today,
                today.plusDays(30), today.minusDays(7)));

        var first = claims.claimNext().claim();
        assertTrue(first != null);
        assertEquals(first.payloadJson(), outbox.findById(first.eventId()).orElseThrow().getPayloadJson());
        assertTrue(claims.markFailed(first, "broker unavailable"));
        assertFalse(claims.markSent(first));
        assertEquals(OutboxStatus.PENDING, outbox.findById(first.eventId()).orElseThrow().getStatus());
        assertTrue(outbox.findById(first.eventId()).orElseThrow().getNextAttemptAt().isAfter(Instant.now()));
    }

    @Test
    void prolongedBrokerOutageDoesNotCreateNewEventAfterCooldown() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(8);
        DefectWarranty warranty = warranties.save(DefectWarranty.builder()
                .siteId(10L).insuranceCompany("보험사")
                .startDate(today.minusDays(1)).endDate(today.plusDays(20)).build());
        assertTrue(alerts.enqueueIfEligible(warranty.getId(), today,
                today.plusDays(30), today.minusDays(7)));

        LocalDate afterCooldown = today.plusDays(8);
        assertFalse(alerts.enqueueIfEligible(warranty.getId(), afterCooldown,
                afterCooldown.plusDays(30), afterCooldown.minusDays(7)));
        assertEquals(1, outbox.count());

        var claimResult = claims.claimNext();
        assertTrue(claimResult.claim() != null, () -> "outbox reason=" + outbox.findAll().get(0).getLastError());
        var claim = claimResult.claim();
        assertTrue(claims.markSent(claim));
        assertEquals(afterCooldown,
                warranties.findById(warranty.getId()).orElseThrow().getLastExpiringAlertSentAt());
        assertFalse(alerts.enqueueIfEligible(warranty.getId(), afterCooldown,
                afterCooldown.plusDays(30), afterCooldown.minusDays(7)));
        assertEquals(1, outbox.count());
    }

    @Test
    void simultaneousSchedulersEnqueueOnlyOnce() throws Exception {
        LocalDate today = LocalDate.of(2026, 9, 20);
        DefectWarranty warranty = warranties.save(DefectWarranty.builder()
                .siteId(9L).insuranceCompany("보험사")
                .startDate(today.minusDays(1)).endDate(today.plusDays(3)).build());
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                start.await();
                return alerts.enqueueIfEligible(warranty.getId(), today,
                        today.plusDays(30), today.minusDays(7));
            });
            var second = pool.submit(() -> {
                start.await();
                return alerts.enqueueIfEligible(warranty.getId(), today,
                        today.plusDays(30), today.minusDays(7));
            });
            start.countDown();
            assertEquals(1, (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, outbox.count());
    }

    @Test
    void 부분_OCR_실패_행은_만료_목록과_알림에서_제외된다() {
        LocalDate today = LocalDate.of(2026, 10, 13);
        DefectWarranty pending = DefectWarranty.createPending(11L, "synthetic.pdf");
        pending.applyOcrResult("보험사", "POL-1", null, today.plusDays(5));
        DefectWarranty warranty = warranties.save(pending);

        List<OcrStatus> eligible = List.of(OcrStatus.MANUAL);
        assertTrue(warranties.findExpiringSoon(today, today.plusDays(30), eligible).isEmpty());
        assertTrue(warranties.findExpiringNotYetAlerted(
                today, today.plusDays(30), today.minusDays(7), eligible).isEmpty());
        assertFalse(alerts.enqueueIfEligible(warranty.getId(), today,
                today.plusDays(30), today.minusDays(7)));
        assertEquals(0, outbox.count());
    }

    @Test
    void 필드가_완전한_OCR도_사용자_확인_전에는_만료_목록과_알림에서_제외된다() {
        LocalDate today = LocalDate.of(2026, 10, 13);
        DefectWarranty extracted = DefectWarranty.createPending(12L, "synthetic.pdf");
        extracted.applyOcrResult("보험사", "POL-2", today.minusDays(1), today.plusDays(5));
        DefectWarranty warranty = warranties.save(extracted);

        assertEquals(OcrStatus.SUCCESS, warranty.getOcrStatus());
        List<OcrStatus> confirmed = List.of(OcrStatus.MANUAL);
        assertTrue(warranties.findExpiringSoon(today, today.plusDays(30), confirmed).isEmpty());
        assertTrue(warranties.findExpiringNotYetAlerted(
                today, today.plusDays(30), today.minusDays(7), confirmed).isEmpty());
        assertFalse(alerts.enqueueIfEligible(warranty.getId(), today,
                today.plusDays(30), today.minusDays(7)));
        assertEquals(0, outbox.count());
    }

    @Test
    void legacyManualDefaultWithoutConfirmationIsNotEligibleUntilUserSaves() {
        LocalDate today = LocalDate.of(2026, 10, 13);
        DefectWarranty warranty = warranties.save(DefectWarranty.builder()
                .siteId(13L).insuranceCompany("보험사")
                .startDate(today.minusDays(1)).endDate(today.plusDays(5)).build());
        jdbc.update("UPDATE defect_warranties SET expiry_confirmed_at = NULL WHERE id = ?", warranty.getId());

        assertTrue(warranties.findExpiringSoon(today, today.plusDays(30),
                List.of(OcrStatus.MANUAL)).isEmpty());
        assertFalse(alerts.enqueueIfEligible(warranty.getId(), today,
                today.plusDays(30), today.minusDays(7)));
        assertEquals(0, outbox.count());

        DefectWarranty reviewed = warranties.findById(warranty.getId()).orElseThrow();
        reviewed.update("보험사", today.minusDays(1), today.plusDays(5), null, null, null);
        warranties.save(reviewed);
        assertTrue(alerts.enqueueIfEligible(warranty.getId(), today,
                today.plusDays(30), today.minusDays(7)));
        assertEquals(1, outbox.count());
    }

    @Test
    void oldPendingBeforeReviewIsSuppressedAndDoesNotBlockFirstConfirmedAlert() {
        LocalDate today = WarrantyDatePolicy.today();
        DefectWarranty warranty = warranties.save(DefectWarranty.builder()
                .siteId(14L).insuranceCompany("보험사")
                .startDate(today.minusDays(1)).endDate(today.plusDays(5)).build());
        assertTrue(alerts.enqueueIfEligible(warranty.getId(), today,
                today.plusDays(30), today.minusDays(7)));
        jdbc.update("UPDATE defect_warranties SET expiry_confirmed_at = NULL WHERE id = ?", warranty.getId());

        assertTrue(claims.claimNext().retired());
        assertEquals(OutboxStatus.SUPPRESSED, outbox.findAll().get(0).getStatus());
        assertEquals("WARRANTY_UNCONFIRMED", outbox.findAll().get(0).getLastError());
        DefectWarranty reviewed = warranties.findById(warranty.getId()).orElseThrow();
        reviewed.update("보험사", today.minusDays(1), today.plusDays(5), null, null, null);
        warranties.save(reviewed);

        assertEquals(null, warranties.findById(warranty.getId()).orElseThrow().getLastExpiringAlertSentAt());
        assertTrue(alerts.enqueueIfEligible(warranty.getId(), today,
                today.plusDays(30), today.minusDays(7)));
        assertEquals(2, outbox.count());
    }

    @Test
    void lateAckAfterReviewCannotRestoreLegacyCooldown() {
        LocalDate today = WarrantyDatePolicy.today();
        DefectWarranty warranty = warranties.save(DefectWarranty.builder()
                .siteId(15L).insuranceCompany("보험사")
                .startDate(today.minusDays(1)).endDate(today.plusDays(5)).build());
        assertTrue(alerts.enqueueIfEligible(warranty.getId(), today,
                today.plusDays(30), today.minusDays(7)));
        var oldClaim = claims.claimNext().claim();
        assertTrue(oldClaim != null);
        jdbc.update("UPDATE defect_warranties SET expiry_confirmed_at = NULL WHERE id = ?", warranty.getId());
        DefectWarranty reviewed = warranties.findById(warranty.getId()).orElseThrow();
        reviewed.update("보험사", today.minusDays(1), today.plusDays(5), null, null, null);
        warranties.save(reviewed);

        assertTrue(claims.markSent(oldClaim));
        assertEquals(null, warranties.findById(warranty.getId()).orElseThrow().getLastExpiringAlertSentAt());
        assertTrue(alerts.enqueueIfEligible(warranty.getId(), today,
                today.plusDays(30), today.minusDays(7)));
        assertEquals(2, outbox.count());
    }

    @Test
    void oldPendingEventDoesNotBlockNewConfirmationOnLastEligibleDay() {
        LocalDate today = WarrantyDatePolicy.today();
        DefectWarranty warranty = warranties.save(DefectWarranty.builder()
                .siteId(16L).insuranceCompany("보험사")
                .startDate(today.minusDays(1)).endDate(today).build());
        assertTrue(alerts.enqueueIfEligible(warranty.getId(), today,
                today.plusDays(30), today.minusDays(7)));
        jdbc.update("UPDATE defect_warranties SET expiry_confirmed_at = NULL WHERE id = ?", warranty.getId());
        DefectWarranty reviewed = warranties.findById(warranty.getId()).orElseThrow();
        reviewed.update("보험사", today.minusDays(1), today, null, null, null);
        warranties.save(reviewed);

        assertTrue(alerts.enqueueIfEligible(warranty.getId(), today,
                today.plusDays(30), today.minusDays(7)));
        assertFalse(alerts.enqueueIfEligible(warranty.getId(), today,
                today.plusDays(30), today.minusDays(7)));
        assertEquals(2, outbox.count());

        assertTrue(claims.claimNext().retired());
        var newClaim = claims.claimNext().claim();
        assertTrue(newClaim != null);
        assertTrue(claims.markSent(newClaim));
        assertEquals(1, outbox.findAll().stream()
                .filter(event -> event.getStatus() == OutboxStatus.SUPPRESSED).count());
        assertEquals(today, warranties.findById(warranty.getId()).orElseThrow()
                .getLastExpiringAlertSentAt());
    }

    @Test
    void malformedLegacyPayloadIsPreservedButNotPublished() {
        String malformed = "{not-json";
        OutboxEvent event = outbox.save(new OutboxEvent(UUID.randomUUID().toString(),
                "warranty.expiring", "123", malformed, Instant.now()));

        assertTrue(claims.claimNext().retired());

        OutboxEvent suppressed = outbox.findById(event.getEventId()).orElseThrow();
        assertEquals(OutboxStatus.SUPPRESSED, suppressed.getStatus());
        assertEquals("MALFORMED_WARRANTY_EVENT", suppressed.getLastError());
        assertEquals(malformed, suppressed.getPayloadJson());
        assertTrue(suppressed.getSuppressedAt() != null);
        assertEquals(null, suppressed.getSentAt());
    }

    @Test
    void expiredLeaseFromUnconfirmedWarrantyIsSuppressed() {
        LocalDate today = WarrantyDatePolicy.today();
        DefectWarranty warranty = warranties.save(DefectWarranty.builder()
                .siteId(17L).insuranceCompany("보험사")
                .startDate(today.minusDays(1)).endDate(today.plusDays(3)).build());
        assertTrue(alerts.enqueueIfEligible(warranty.getId(), today,
                today.plusDays(30), today.minusDays(7)));
        OutboxEvent event = outbox.findAll().get(0);
        String payload = event.getPayloadJson();
        event.claim("expired", Instant.now().minusSeconds(1));
        outbox.save(event);
        jdbc.update("UPDATE defect_warranties SET expiry_confirmed_at = NULL WHERE id = ?", warranty.getId());

        assertTrue(claims.claimNext().retired());

        OutboxEvent suppressed = outbox.findById(event.getEventId()).orElseThrow();
        assertEquals(OutboxStatus.SUPPRESSED, suppressed.getStatus());
        assertEquals("WARRANTY_UNCONFIRMED", suppressed.getLastError());
        assertEquals(payload, suppressed.getPayloadJson());
        assertEquals(1, suppressed.getAttempts());
    }

    @Test
    void changedWarrantyFactsAreSuppressedWithoutChangingPayload() {
        LocalDate today = WarrantyDatePolicy.today();
        DefectWarranty warranty = warranties.save(DefectWarranty.builder()
                .siteId(18L).insuranceCompany("보험사")
                .startDate(today.minusDays(1)).endDate(today.plusDays(3)).build());
        assertTrue(alerts.enqueueIfEligible(warranty.getId(), today,
                today.plusDays(30), today.minusDays(7)));
        OutboxEvent event = outbox.findAll().get(0);
        String payload = event.getPayloadJson();
        jdbc.update("UPDATE defect_warranties SET insurance_company = ? WHERE id = ?",
                "변경 보험사", warranty.getId());

        assertTrue(claims.claimNext().retired());

        OutboxEvent suppressed = outbox.findById(event.getEventId()).orElseThrow();
        assertEquals(OutboxStatus.SUPPRESSED, suppressed.getStatus());
        assertEquals("WARRANTY_FACTS_CHANGED", suppressed.getLastError());
        assertEquals(payload, suppressed.getPayloadJson());
    }

    @TestConfiguration
    static class JacksonConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }
    }
}
