package com.buildflow.site.domain.site.service;

import com.buildflow.site.domain.client.repository.ClientRepository;
import com.buildflow.site.domain.site.entity.Site;
import com.buildflow.site.domain.site.repository.SiteRepository;
import com.buildflow.site.global.exception.BusinessException;
import com.buildflow.site.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SiteServiceDeletionTest {

    private final SiteRepository siteRepository = mock(SiteRepository.class);
    private final SiteService siteService = new SiteService(siteRepository, mock(ClientRepository.class));

    @Test
    void existingSiteCannotBePhysicallyDeleted() {
        Site site = mock(Site.class);
        when(siteRepository.findById(42L)).thenReturn(Optional.of(site));

        BusinessException error = assertThrows(BusinessException.class, () -> siteService.delete(42L));

        assertEquals(ErrorCode.SITE_DELETION_DISABLED, error.getErrorCode());
        verify(siteRepository, never()).delete(site);
    }

    @Test
    void missingSiteStillReturnsNotFound() {
        when(siteRepository.findById(99L)).thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class, () -> siteService.delete(99L));

        assertEquals(ErrorCode.SITE_NOT_FOUND, error.getErrorCode());
    }
}
