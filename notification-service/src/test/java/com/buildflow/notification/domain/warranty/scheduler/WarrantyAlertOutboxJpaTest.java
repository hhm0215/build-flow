package com.buildflow.notification.domain.warranty.scheduler;

import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import com.buildflow.notification.global.config.JpaAuditingConfig;
import com.buildflow.notification.global.kafka.KafkaProducerService;
import com.buildflow.notification.global.outbox.OutboxClaimService;
import com.buildflow.notification.global.outbox.OutboxEvent;
import com.buildflow.notification.global.outbox.OutboxEventRepository;
import com.buildflow.notification.global.outbox.OutboxEventService;
import com.buildflow.notification.global.outbox.OutboxStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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

    @BeforeEach
    void clean() {
        outbox.deleteAll();
        warranties.deleteAll();
    }

    @Test
    void cooldownAndOutboxCommitTogetherWithoutDuplicate() {
        LocalDate today = LocalDate.of(2026, 9, 20);
        DefectWarranty warranty = warranties.save(DefectWarranty.builder()
                .siteId(7L).endDate(today.plusDays(5)).insuranceCompany("보험사").build());

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
        LocalDate today = LocalDate.of(2026, 9, 20);
        DefectWarranty warranty = warranties.save(DefectWarranty.builder()
                .siteId(8L).endDate(today.plusDays(2)).build());
        assertTrue(alerts.enqueueIfEligible(warranty.getId(), today,
                today.plusDays(30), today.minusDays(7)));

        var first = claims.claimNext().orElseThrow();
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
                .siteId(10L).endDate(today.plusDays(20)).build());
        assertTrue(alerts.enqueueIfEligible(warranty.getId(), today,
                today.plusDays(30), today.minusDays(7)));

        LocalDate afterCooldown = today.plusDays(8);
        assertFalse(alerts.enqueueIfEligible(warranty.getId(), afterCooldown,
                afterCooldown.plusDays(30), afterCooldown.minusDays(7)));
        assertEquals(1, outbox.count());

        var claim = claims.claimNext().orElseThrow();
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
                .siteId(9L).endDate(today.plusDays(3)).build());
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

    @TestConfiguration
    static class JacksonConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
