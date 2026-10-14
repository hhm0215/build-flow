package com.buildflow.notification.domain.warranty.service;

import com.buildflow.notification.domain.notification.repository.NotificationRepository;
import com.buildflow.notification.domain.notification.entity.Notification;
import com.buildflow.notification.domain.notification.repository.ProcessedNotificationEventRepository;
import com.buildflow.notification.domain.notification.service.NotificationService;
import com.buildflow.notification.domain.warranty.WarrantyDatePolicy;
import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import com.buildflow.notification.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@DataJpaTest
@ActiveProfiles("test")
@Import({WarrantyExpiryNotificationService.class, NotificationService.class, JpaAuditingConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WarrantyExpiryNotificationJpaTest {

    @Autowired private WarrantyExpiryNotificationService expiryNotifications;
    @Autowired private DefectWarrantyRepository warranties;
    @SpyBean private NotificationRepository notifications;
    @Autowired private ProcessedNotificationEventRepository receipts;

    @BeforeEach
    void clean() {
        notifications.deleteAll();
        receipts.deleteAll();
        warranties.deleteAll();
    }

    @Test
    void currentManuallyConfirmedWarrantyCreatesOneNotificationWithCurrentDays() {
        LocalDate today = WarrantyDatePolicy.today();
        DefectWarranty warranty = warranties.save(DefectWarranty.builder()
                .siteId(7L).insuranceCompany("보험사")
                .startDate(today.minusDays(1)).endDate(today.plusDays(5)).build());
        String eventId = UUID.randomUUID().toString();

        expiryNotifications.consume(eventId, warranty.getId(), 7L, "보험사", warranty.getEndDate());
        expiryNotifications.consume(eventId, warranty.getId(), 7L, "보험사", warranty.getEndDate());

        assertEquals(1, receipts.count());
        assertEquals(1, notifications.count());
        assertEquals("하자보증보험(ID: " + warranty.getId() + ", 보험사) 만료까지 5일 남았습니다.",
                notifications.findAll().get(0).getMessage());
    }

    @Test
    void unconfirmedOcrAndDeletedWarrantyAreRecordedWithoutNotification() {
        DefectWarranty pending = warranties.save(DefectWarranty.createPending(7L, "synthetic.pdf"));
        String pendingEventId = UUID.randomUUID().toString();

        expiryNotifications.consume(pendingEventId, pending.getId(), 7L, "보험사",
                WarrantyDatePolicy.today().plusDays(5));
        warranties.deleteById(pending.getId());
        expiryNotifications.consume(UUID.randomUUID().toString(), pending.getId(), 7L, "보험사",
                WarrantyDatePolicy.today().plusDays(5));

        assertEquals(2, receipts.count());
        assertEquals(0, notifications.count());
    }

    @Test
    void changedSnapshotCannotCreateNotificationOnLaterReplay() {
        LocalDate today = WarrantyDatePolicy.today();
        DefectWarranty warranty = warranties.save(DefectWarranty.builder()
                .siteId(7L).insuranceCompany("새 보험사")
                .startDate(today.minusDays(1)).endDate(today.plusDays(5)).build());
        String eventId = UUID.randomUUID().toString();

        expiryNotifications.consume(eventId, warranty.getId(), 7L, "옛 보험사", warranty.getEndDate());
        expiryNotifications.consume(eventId, warranty.getId(), 7L, "새 보험사", warranty.getEndDate());

        assertEquals(1, receipts.count());
        assertEquals(0, notifications.count());
    }

    @Test
    void expiredWarrantyIsIgnoredEvenIfSnapshotMatches() {
        LocalDate today = WarrantyDatePolicy.today();
        DefectWarranty warranty = warranties.save(DefectWarranty.builder()
                .siteId(7L).insuranceCompany("보험사")
                .startDate(today.minusDays(30)).endDate(today.minusDays(1)).build());

        expiryNotifications.consume(UUID.randomUUID().toString(), warranty.getId(), 7L,
                "보험사", warranty.getEndDate());

        assertEquals(1, receipts.count());
        assertEquals(0, notifications.count());
    }

    @Test
    void notificationFailureRollsBackReceiptForKafkaRetry() {
        LocalDate today = WarrantyDatePolicy.today();
        DefectWarranty warranty = warranties.save(DefectWarranty.builder()
                .siteId(7L).insuranceCompany("보험사")
                .startDate(today.minusDays(1)).endDate(today.plusDays(5)).build());
        String eventId = UUID.randomUUID().toString();
        doThrow(new IllegalStateException("notification write failed"))
                .when(notifications).save(any(Notification.class));

        assertThrows(IllegalStateException.class, () -> expiryNotifications.consume(
                eventId, warranty.getId(), 7L, "보험사", warranty.getEndDate()));

        assertFalse(receipts.existsById(eventId));
        assertEquals(0, notifications.count());
    }
}
