package com.buildflow.site.domain.site.service;

import com.buildflow.site.domain.client.repository.ClientRepository;
import com.buildflow.site.domain.site.dto.SiteCreateRequest;
import com.buildflow.site.domain.site.dto.SiteStatusUpdateRequest;
import com.buildflow.site.domain.site.entity.Site;
import com.buildflow.site.domain.site.entity.SiteStatus;
import com.buildflow.site.domain.site.repository.SiteRepository;
import com.buildflow.site.global.exception.BusinessException;
import com.buildflow.site.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.EnumSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SiteServiceStatusTest {

    private final SiteRepository siteRepository = mock(SiteRepository.class);
    private final SiteService siteService = new SiteService(siteRepository, mock(ClientRepository.class));

    private static SiteCreateRequest createRequest(SiteStatus status) {
        SiteCreateRequest request = new SiteCreateRequest();
        ReflectionTestUtils.setField(request, "siteName", "[QA] 합성 현장");
        ReflectionTestUtils.setField(request, "status", status);
        return request;
    }

    private static SiteStatusUpdateRequest statusRequest(SiteStatus status) {
        SiteStatusUpdateRequest request = new SiteStatusUpdateRequest();
        ReflectionTestUtils.setField(request, "status", status);
        return request;
    }

    private Site existing(SiteStatus status) {
        Site site = Site.builder().siteName("[QA] 합성 현장").status(status).build();
        when(siteRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(site));
        return site;
    }

    @Test
    void createDefaultsToInProgressAndAcceptsBidding() {
        when(siteRepository.save(any(Site.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals(SiteStatus.IN_PROGRESS, siteService.create(createRequest(null)).getStatus());
        assertEquals(SiteStatus.BIDDING, siteService.create(createRequest(SiteStatus.BIDDING)).getStatus());
        assertEquals(SiteStatus.IN_PROGRESS, siteService.create(createRequest(SiteStatus.IN_PROGRESS)).getStatus());
    }

    @Test
    void createRejectsLaterStagesAsInitialStatus() {
        for (SiteStatus status : EnumSet.of(SiteStatus.SETTLING, SiteStatus.WARRANTY, SiteStatus.COMPLETED)) {
            assertEquals(ErrorCode.SITE_INITIAL_STATUS_NOT_ALLOWED, assertThrows(BusinessException.class,
                    () -> siteService.create(createRequest(status))).getErrorCode(), status.name());
        }
    }

    @Test
    void biddingMovesOnlyToInProgress() {
        Site site = existing(SiteStatus.BIDDING);
        for (SiteStatus next : EnumSet.of(SiteStatus.SETTLING, SiteStatus.WARRANTY, SiteStatus.COMPLETED)) {
            assertEquals(ErrorCode.SITE_STATUS_TRANSITION_NOT_ALLOWED, assertThrows(BusinessException.class,
                    () -> siteService.changeStatus(7L, statusRequest(next))).getErrorCode(), next.name());
            assertEquals(SiteStatus.BIDDING, site.getStatus());
        }

        assertEquals(SiteStatus.IN_PROGRESS, siteService.changeStatus(7L, statusRequest(SiteStatus.IN_PROGRESS)).getStatus());
    }

    @Test
    void wonSitesCannotGoBackToBidding() {
        for (SiteStatus current : EnumSet.of(SiteStatus.IN_PROGRESS, SiteStatus.SETTLING,
                SiteStatus.WARRANTY, SiteStatus.COMPLETED)) {
            Site site = existing(current);
            assertEquals(ErrorCode.SITE_STATUS_TRANSITION_NOT_ALLOWED, assertThrows(BusinessException.class,
                    () -> siteService.changeStatus(7L, statusRequest(SiteStatus.BIDDING))).getErrorCode(), current.name());
            assertEquals(current, site.getStatus());
        }
    }

    @Test
    void transitionRuleKeepsExistingFreedomAfterWinning() {
        assertTrue(SiteStatus.BIDDING.canTransitionTo(SiteStatus.BIDDING));
        assertTrue(SiteStatus.COMPLETED.canTransitionTo(SiteStatus.IN_PROGRESS));
        assertTrue(SiteStatus.IN_PROGRESS.canTransitionTo(SiteStatus.WARRANTY));
        assertFalse(SiteStatus.IN_PROGRESS.canTransitionTo(null));
        assertEquals("수주 전", SiteStatus.labelOf("BIDDING"));
        assertEquals("UNKNOWN", SiteStatus.labelOf("UNKNOWN"));
    }
}
