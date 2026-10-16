package com.buildflow.site.domain.site.service;

import com.buildflow.site.domain.client.repository.ClientRepository;
import com.buildflow.site.domain.site.dto.SiteResponse;
import com.buildflow.site.domain.site.dto.SiteStatusUpdateRequest;
import com.buildflow.site.domain.site.dto.SiteUpdateRequest;
import com.buildflow.site.domain.site.entity.Site;
import com.buildflow.site.domain.site.repository.SiteRepository;
import com.buildflow.site.global.exception.BusinessException;
import com.buildflow.site.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SiteServiceArchiveTest {

    private final SiteRepository siteRepository = mock(SiteRepository.class);
    private final SiteService siteService = new SiteService(siteRepository, mock(ClientRepository.class));

    private Site site(String name) {
        return Site.builder().siteName(name).build();
    }

    @Test
    void archiveSetsTimestampAndIsIdempotent() {
        Site site = site("[QA] 보관 대상");
        when(siteRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(site));

        SiteResponse first = siteService.archive(7L);
        LocalDateTime archivedAt = first.getArchivedAt();
        SiteResponse second = siteService.archive(7L);

        assertNotNull(archivedAt);
        assertEquals(0, archivedAt.getNano());
        assertEquals(archivedAt, second.getArchivedAt());
    }

    @Test
    void restoreClearsArchiveAndAllowsEditingAgain() {
        Site site = site("[QA] 복원 대상");
        site.archive(LocalDateTime.of(2026, 10, 17, 9, 0));
        when(siteRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(site));

        assertNull(siteService.restore(7L).getArchivedAt());
    }

    @Test
    void archivedSiteCannotBeEditedOrChangeStatus() {
        Site site = site("[QA] 보관됨");
        site.archive(LocalDateTime.of(2026, 10, 17, 9, 0));
        when(siteRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(site));

        assertEquals(ErrorCode.SITE_ARCHIVED, assertThrows(BusinessException.class,
                () -> siteService.update(7L, mock(SiteUpdateRequest.class))).getErrorCode());
        assertEquals(ErrorCode.SITE_ARCHIVED, assertThrows(BusinessException.class,
                () -> siteService.changeStatus(7L, mock(SiteStatusUpdateRequest.class))).getErrorCode());
        assertEquals("[QA] 보관됨", site.getSiteName());
    }

    @Test
    void missingSiteArchiveReturnsNotFound() {
        when(siteRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertEquals(ErrorCode.SITE_NOT_FOUND,
                assertThrows(BusinessException.class, () -> siteService.archive(99L)).getErrorCode());
        assertEquals(ErrorCode.SITE_NOT_FOUND,
                assertThrows(BusinessException.class, () -> siteService.restore(99L)).getErrorCode());
    }

    @Test
    void listFilterSeparatesActiveAndArchivedButDefaultsToAll() {
        Site active = site("[QA] 진행");
        Site archived = site("[QA] 보관");
        archived.archive(LocalDateTime.of(2026, 10, 17, 9, 0));
        when(siteRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(active, archived));

        assertEquals(List.of("[QA] 진행", "[QA] 보관"), names(siteService.findAll(null, null)));
        assertEquals(List.of("[QA] 진행"), names(siteService.findAll(null, false)));
        assertEquals(List.of("[QA] 보관"), names(siteService.findAll(null, true)));
    }

    private List<String> names(List<SiteResponse> responses) {
        return responses.stream().map(SiteResponse::getSiteName).toList();
    }
}
