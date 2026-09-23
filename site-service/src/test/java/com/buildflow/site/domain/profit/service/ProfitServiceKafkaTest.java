package com.buildflow.site.domain.profit.service;

import com.buildflow.site.domain.profit.entity.SiteProfit;
import com.buildflow.site.domain.profit.event.ProfitEventType;
import com.buildflow.site.domain.profit.repository.ProcessedProfitEventRepository;
import com.buildflow.site.domain.profit.repository.PurchaseProfitProjectionRepository;
import com.buildflow.site.domain.profit.repository.SiteProfitRepository;
import com.buildflow.site.domain.site.entity.Site;
import com.buildflow.site.domain.site.repository.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
@DataJpaTest
@ActiveProfiles("test")
@Import(ProfitService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ProfitServiceKafkaTest {

    private final ProfitService profitService;
    private final SiteRepository siteRepository;
    private final SiteProfitRepository profitRepository;
    private final ProcessedProfitEventRepository processedRepository;
    private final PurchaseProfitProjectionRepository purchaseProjectionRepository;

    private Long siteId;

    ProfitServiceKafkaTest(ProfitService profitService, SiteRepository siteRepository,
                           SiteProfitRepository profitRepository,
                           ProcessedProfitEventRepository processedRepository,
                           PurchaseProfitProjectionRepository purchaseProjectionRepository) {
        this.profitService = profitService;
        this.siteRepository = siteRepository;
        this.profitRepository = profitRepository;
        this.processedRepository = processedRepository;
        this.purchaseProjectionRepository = purchaseProjectionRepository;
    }

    @BeforeEach
    void setUp() {
        processedRepository.deleteAll();
        purchaseProjectionRepository.deleteAll();
        profitRepository.deleteAll();
        siteRepository.deleteAll();
        siteId = siteRepository.saveAndFlush(Site.builder().siteName("동시 처리 현장").build()).getId();
    }

    @Test
    void duplicateEventChangesProfitOnlyOnce() {
        String eventId = UUID.randomUUID().toString();

        assertTrue(profitService.applyEvent(eventId, ProfitEventType.ESTIMATE_PARSED,
                siteId, new BigDecimal("100.00"), null));
        assertFalse(profitService.applyEvent(eventId, ProfitEventType.ESTIMATE_PARSED,
                siteId, new BigDecimal("100.00"), null));

        SiteProfit profit = profitRepository.findBySiteId(siteId).orElseThrow();
        assertEquals(0, profit.getTotalEstimateAmount().compareTo(new BigDecimal("100.00")));
        assertEquals(1, processedRepository.count());
        assertEquals(1, profitRepository.count());
    }

    @Test
    void concurrentDeliveryOfSameEventIdIsAppliedOnce() throws Exception {
        String eventId = UUID.randomUUID().toString();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return profitService.applyPurchaseEvent(eventId, ProfitEventType.PURCHASE_REGISTERED,
                            1L, siteId, 1L, new BigDecimal("25.00"));
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            boolean first = futures.get(0).get(10, TimeUnit.SECONDS);
            boolean second = futures.get(1).get(10, TimeUnit.SECONDS);

            assertTrue(first ^ second);
            assertEquals(0, profitRepository.findBySiteId(siteId).orElseThrow()
                    .getTotalPurchaseAmount().compareTo(new BigDecimal("25.00")));
            assertEquals(1, processedRepository.count());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void ledgerWriteFailureRollsBackFirstProfitInsert() {
        String eventId = UUID.randomUUID().toString();

        // The ledger insert is flushed first; the site_profits DECIMAL(15,2)
        // column then rejects this value, so both writes must roll back.
        assertThrows(RuntimeException.class, () -> profitService.applyPurchaseEvent(
                eventId, ProfitEventType.PURCHASE_REGISTERED, 1L, siteId,
                1L, new BigDecimal("100000000000000.00")));

        assertTrue(profitRepository.findBySiteId(siteId).isEmpty());
        assertFalse(purchaseProjectionRepository.existsById(1L));
        assertFalse(processedRepository.existsById(eventId));
    }

    @Test
    void eventsForSameSitePreserveAmountsAndCreateOneProfitRow() throws Exception {
        int eventCount = 4;
        CountDownLatch ready = new CountDownLatch(eventCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(eventCount);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < eventCount; i++) {
                String eventId = UUID.randomUUID().toString();
                long purchaseId = i + 1L;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return profitService.applyPurchaseEvent(eventId, ProfitEventType.PURCHASE_REGISTERED,
                            purchaseId, siteId, 1L, new BigDecimal("10.00"));
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<Boolean> future : futures) {
                assertTrue(future.get(10, TimeUnit.SECONDS));
            }

            SiteProfit profit = profitRepository.findBySiteId(siteId).orElseThrow();
            assertEquals(0, profit.getTotalPurchaseAmount().compareTo(new BigDecimal("40.00")));
            assertEquals(1, profitRepository.count());
            assertEquals(eventCount, processedRepository.count());
            assertEquals(eventCount, purchaseProjectionRepository.count());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
