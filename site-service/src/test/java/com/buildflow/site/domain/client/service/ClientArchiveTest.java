package com.buildflow.site.domain.client.service;

import com.buildflow.site.domain.client.dto.ClientResponse;
import com.buildflow.site.domain.client.dto.ClientUpdateRequest;
import com.buildflow.site.domain.client.entity.Client;
import com.buildflow.site.domain.client.repository.ClientRepository;
import com.buildflow.site.domain.site.dto.SiteCreateRequest;
import com.buildflow.site.domain.site.dto.SiteUpdateRequest;
import com.buildflow.site.domain.site.entity.Site;
import com.buildflow.site.domain.site.repository.SiteRepository;
import com.buildflow.site.domain.site.service.SiteService;
import com.buildflow.site.global.exception.BusinessException;
import com.buildflow.site.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientArchiveTest {

    private final ClientRepository clientRepository = mock(ClientRepository.class);
    private final SiteRepository siteRepository = mock(SiteRepository.class);
    private final ClientService clientService = new ClientService(clientRepository);
    private final SiteService siteService = new SiteService(siteRepository, clientRepository);

    private Client client(long id, String name, boolean archived) {
        Client client = Client.builder().companyName(name).build();
        ReflectionTestUtils.setField(client, "id", id);
        if (archived) {
            client.archive(LocalDateTime.of(2026, 10, 17, 9, 0));
        }
        when(clientRepository.findById(id)).thenReturn(Optional.of(client));
        when(clientRepository.findByIdForUpdate(id)).thenReturn(Optional.of(client));
        return client;
    }

    @Test
    void archiveIsIdempotentAndRestoreClears() {
        client(3L, "[QA] 거래처", false);

        ClientResponse archived = clientService.archive(3L);
        assertNotNull(archived.getArchivedAt());
        assertEquals(0, archived.getArchivedAt().getNano());
        assertEquals(archived.getArchivedAt(), clientService.archive(3L).getArchivedAt());
        assertNull(clientService.restore(3L).getArchivedAt());
    }

    @Test
    void archivedClientCannotBeEdited() {
        client(3L, "[QA] 보관 거래처", true);

        assertEquals(ErrorCode.CLIENT_ARCHIVED, assertThrows(BusinessException.class,
                () -> clientService.update(3L, mock(ClientUpdateRequest.class))).getErrorCode());
    }

    @Test
    void listFilterSeparatesArchivedButDefaultsToAll() {
        Client active = Client.builder().companyName("[QA] 진행").build();
        Client archived = Client.builder().companyName("[QA] 보관").build();
        archived.archive(LocalDateTime.of(2026, 10, 17, 9, 0));
        when(clientRepository.findAll()).thenReturn(List.of(active, archived));

        assertEquals(2, clientService.findAll(null).size());
        assertEquals("[QA] 진행", clientService.findAll(false).get(0).getCompanyName());
        assertEquals("[QA] 보관", clientService.findAll(true).get(0).getCompanyName());
    }

    @Test
    void archivedClientCannotBeLinkedToNewSite() {
        client(3L, "[QA] 보관 거래처", true);
        SiteCreateRequest request = mock(SiteCreateRequest.class);
        when(request.getSiteName()).thenReturn("[QA] 새 현장");
        when(request.getClientId()).thenReturn(3L);

        assertEquals(ErrorCode.CLIENT_ARCHIVED_NOT_ASSIGNABLE, assertThrows(BusinessException.class,
                () -> siteService.create(request)).getErrorCode());
    }

    @Test
    void siteKeepingItsArchivedClientCanStillBeEditedButCannotSwitchToAnotherArchivedClient() {
        Client current = client(3L, "[QA] 기존 보관 거래처", true);
        client(4L, "[QA] 다른 보관 거래처", true);
        Site site = Site.builder().siteName("[QA] 현장").client(current).build();
        when(siteRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(site));
        when(siteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SiteUpdateRequest keep = mock(SiteUpdateRequest.class);
        when(keep.getSiteName()).thenReturn("[QA] 현장 이름 수정");
        when(keep.getClientId()).thenReturn(3L);
        assertDoesNotThrow(() -> siteService.update(7L, keep));

        SiteUpdateRequest switchTo = mock(SiteUpdateRequest.class);
        when(switchTo.getSiteName()).thenReturn("[QA] 현장");
        when(switchTo.getClientId()).thenReturn(4L);
        assertEquals(ErrorCode.CLIENT_ARCHIVED_NOT_ASSIGNABLE, assertThrows(BusinessException.class,
                () -> siteService.update(7L, switchTo)).getErrorCode());
    }
}
