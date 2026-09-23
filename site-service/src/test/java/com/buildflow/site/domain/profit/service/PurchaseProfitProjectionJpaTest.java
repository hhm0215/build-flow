package com.buildflow.site.domain.profit.service;

import com.buildflow.site.domain.profit.entity.PurchaseProfitProjection;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@ActiveProfiles("test")
@Import(ProfitService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PurchaseProfitProjectionJpaTest {

    private static final Long PURCHASE_ID = 11L;

    private final ProfitService profitService;
    private final SiteRepository siteRepository;
    private final SiteProfitRepository profitRepository;
    private final PurchaseProfitProjectionRepository projectionRepository;
    private final ProcessedProfitEventRepository processedRepository;

    private Long siteId;

    PurchaseProfitProjectionJpaTest(ProfitService profitService, SiteRepository siteRepository,
                                    SiteProfitRepository profitRepository,
                                    PurchaseProfitProjectionRepository projectionRepository,
                                    ProcessedProfitEventRepository processedRepository) {
        this.profitService = profitService;
        this.siteRepository = siteRepository;
        this.profitRepository = profitRepository;
        this.projectionRepository = projectionRepository;
        this.processedRepository = processedRepository;
    }

    @BeforeEach
    void setUp() {
        reset("순서 역전 현장");
    }

    @Test
    void allLifecyclePermutationsConvergeToLatestDeleteTombstone() {
        Snapshot registered = new Snapshot(ProfitEventType.PURCHASE_REGISTERED, 1L, "100.00");
        Snapshot updated = new Snapshot(ProfitEventType.PURCHASE_UPDATED, 2L, "150.00");
        Snapshot deleted = new Snapshot(ProfitEventType.PURCHASE_DELETED, 3L, "150.00");
        List<List<Snapshot>> permutations = List.of(
                List.of(registered, updated, deleted),
                List.of(registered, deleted, updated),
                List.of(updated, registered, deleted),
                List.of(updated, deleted, registered),
                List.of(deleted, registered, updated),
                List.of(deleted, updated, registered)
        );

        for (int i = 0; i < permutations.size(); i++) {
            if (i > 0) {
                reset("순서 역전 현장 " + i);
            }
            for (Snapshot snapshot : permutations.get(i)) {
                apply(PURCHASE_ID, snapshot);
            }

            PurchaseProfitProjection projection = projectionRepository.findById(PURCHASE_ID).orElseThrow();
            assertEquals(3L, projection.getLastRevision());
            assertTrue(projection.isDeleted());
            assertAmount("150.00", projection.getCurrentAmount());
            assertAmount("0.00", totalPurchaseAmount());
            assertEquals(3, processedRepository.count());
        }
    }

    @Test
    void updateBeforeRegisterUsesFullStateAndStaleRegisterCannotOverwrite() {
        assertTrue(apply(PURCHASE_ID,
                new Snapshot(ProfitEventType.PURCHASE_UPDATED, 2L, "150.00")));
        assertFalse(apply(PURCHASE_ID,
                new Snapshot(ProfitEventType.PURCHASE_REGISTERED, 1L, "100.00")));

        PurchaseProfitProjection projection = projectionRepository.findById(PURCHASE_ID).orElseThrow();
        assertEquals(2L, projection.getLastRevision());
        assertFalse(projection.isDeleted());
        assertAmount("150.00", projection.getCurrentAmount());
        assertAmount("150.00", totalPurchaseAmount());
        assertEquals(2, processedRepository.count());
    }

    @Test
    void equalRevisionIdenticalStateIsNoOpButConflictingStateRollsBack() {
        assertTrue(apply(PURCHASE_ID,
                new Snapshot(ProfitEventType.PURCHASE_REGISTERED, 1L, "100.00")));
        assertFalse(apply(PURCHASE_ID,
                new Snapshot(ProfitEventType.PURCHASE_REGISTERED, 1L, "100.00")));

        long processedBeforeConflict = processedRepository.count();
        assertThrows(IllegalArgumentException.class, () -> apply(PURCHASE_ID,
                new Snapshot(ProfitEventType.PURCHASE_REGISTERED, 1L, "120.00")));

        assertEquals(processedBeforeConflict, processedRepository.count());
        assertAmount("100.00", projectionRepository.findById(PURCHASE_ID).orElseThrow().getCurrentAmount());
        assertAmount("100.00", totalPurchaseAmount());
    }

    @Test
    void interleavedPurchasesTrackRevisionsIndependently() {
        apply(11L, new Snapshot(ProfitEventType.PURCHASE_REGISTERED, 1L, "100.00"));
        apply(22L, new Snapshot(ProfitEventType.PURCHASE_REGISTERED, 1L, "30.00"));
        apply(11L, new Snapshot(ProfitEventType.PURCHASE_UPDATED, 2L, "150.00"));
        apply(22L, new Snapshot(ProfitEventType.PURCHASE_DELETED, 2L, "30.00"));

        assertAmount("150.00", totalPurchaseAmount());
        assertEquals(2L, projectionRepository.findById(11L).orElseThrow().getLastRevision());
        assertTrue(projectionRepository.findById(22L).orElseThrow().isDeleted());
        assertEquals(2, projectionRepository.count());
    }

    @Test
    void existingProjectionRejectsSiteChangeAndDeletedProjectionRejectsResurrection() {
        apply(PURCHASE_ID, new Snapshot(ProfitEventType.PURCHASE_REGISTERED, 1L, "100.00"));
        Long otherSiteId = siteRepository.saveAndFlush(Site.builder().siteName("다른 현장").build()).getId();

        assertThrows(IllegalArgumentException.class, () -> profitService.applyPurchaseEvent(
                UUID.randomUUID().toString(), ProfitEventType.PURCHASE_UPDATED,
                PURCHASE_ID, otherSiteId, 2L, new BigDecimal("120.00")));

        apply(PURCHASE_ID, new Snapshot(ProfitEventType.PURCHASE_DELETED, 2L, "100.00"));
        assertThrows(IllegalArgumentException.class, () -> apply(PURCHASE_ID,
                new Snapshot(ProfitEventType.PURCHASE_UPDATED, 3L, "130.00")));

        assertAmount("0.00", totalPurchaseAmount());
        PurchaseProfitProjection projection = projectionRepository.findById(PURCHASE_ID).orElseThrow();
        assertEquals(2L, projection.getLastRevision());
        assertTrue(projection.isDeleted());
    }

    @Test
    void invalidRevisionForEventTypeRollsBackWithoutCreatingState() {
        List<Snapshot> invalidSnapshots = List.of(
                new Snapshot(ProfitEventType.PURCHASE_REGISTERED, 2L, "100.00"),
                new Snapshot(ProfitEventType.PURCHASE_UPDATED, 1L, "120.00"),
                new Snapshot(ProfitEventType.PURCHASE_DELETED, 1L, "120.00")
        );

        for (Snapshot snapshot : invalidSnapshots) {
            assertThrows(IllegalArgumentException.class, () -> apply(PURCHASE_ID, snapshot));
        }

        assertEquals(0, processedRepository.count());
        assertEquals(0, projectionRepository.count());
        assertEquals(0, profitRepository.count());
    }

    private boolean apply(Long purchaseId, Snapshot snapshot) {
        return profitService.applyPurchaseEvent(UUID.randomUUID().toString(), snapshot.type(),
                purchaseId, siteId, snapshot.revision(), new BigDecimal(snapshot.amount()));
    }

    private BigDecimal totalPurchaseAmount() {
        return profitRepository.findBySiteId(siteId)
                .map(SiteProfit::getTotalPurchaseAmount)
                .orElse(BigDecimal.ZERO);
    }

    private void reset(String siteName) {
        processedRepository.deleteAll();
        projectionRepository.deleteAll();
        profitRepository.deleteAll();
        siteRepository.deleteAll();
        siteId = siteRepository.saveAndFlush(Site.builder().siteName(siteName).build()).getId();
    }

    private void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, actual.compareTo(new BigDecimal(expected)));
    }

    private record Snapshot(ProfitEventType type, long revision, String amount) {
    }
}
