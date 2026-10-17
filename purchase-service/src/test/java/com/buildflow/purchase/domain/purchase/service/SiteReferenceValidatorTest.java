package com.buildflow.purchase.domain.purchase.service;

import com.buildflow.purchase.global.exception.BusinessException;
import com.buildflow.purchase.global.exception.ErrorCode;
import com.buildflow.purchase.infra.feign.SiteReferenceClient;
import com.buildflow.purchase.infra.feign.SiteReferenceResponse;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SiteReferenceValidatorTest {

    private final SiteReferenceClient client = mock(SiteReferenceClient.class);
    private final SiteReferenceValidator validator = new SiteReferenceValidator(client);

    @Test
    void matchingExistingSitePasses() {
        when(client.findById(7L)).thenReturn(new SiteReferenceResponse(true,
                new SiteReferenceResponse.SiteIdentity(7L)));

        assertDoesNotThrow(() -> validator.requireExistingSite(7L));
    }

    @Test
    void decodesSiteServiceResponseWithAdditionalFields() throws Exception {
        String json = """
                {"success":true,"data":{"id":7,"siteName":"합성 현장","status":"IN_PROGRESS"},"error":null}
                """;
        SiteReferenceResponse response = new ObjectMapper().readValue(json, SiteReferenceResponse.class);

        assertEquals(7L, response.data().id());
        when(client.findById(7L)).thenReturn(response);
        assertDoesNotThrow(() -> validator.requireExistingSite(7L));
    }

    @Test
    void archivedSiteIsRejected() throws Exception {
        String json = """
                {"success":true,"data":{"id":7,"siteName":"합성 현장","archivedAt":"2026-10-17T09:30:00"},"error":null}
                """;
        when(client.findById(7L)).thenReturn(new ObjectMapper().readValue(json, SiteReferenceResponse.class));

        assertEquals(ErrorCode.SITE_ARCHIVED,
                assertThrows(BusinessException.class, () -> validator.requireExistingSite(7L)).getErrorCode());
    }

    @Test
    void existingDocumentOnArchivedSiteIsLockedButOrphansStayEditable() throws Exception {
        String archived = """
                {"success":true,"data":{"id":7,"archivedAt":"2026-10-17T09:30:00"},"error":null}
                """;
        doReturn(new ObjectMapper().readValue(archived, SiteReferenceResponse.class)).when(client).findById(7L);
        assertEquals(ErrorCode.SITE_ARCHIVED_DOCUMENT_LOCKED,
                assertThrows(BusinessException.class, () -> validator.requireSiteNotArchived(7L)).getErrorCode());

        doReturn(new SiteReferenceResponse(true, new SiteReferenceResponse.SiteIdentity(8L))).when(client).findById(8L);
        assertDoesNotThrow(() -> validator.requireSiteNotArchived(8L));

        doThrow(siteError(404)).when(client).findById(9L);
        assertDoesNotThrow(() -> validator.requireSiteNotArchived(9L));

        doThrow(siteError(503)).when(client).findById(10L);
        assertEquals(ErrorCode.SITE_REFERENCE_UNAVAILABLE,
                assertThrows(BusinessException.class, () -> validator.requireSiteNotArchived(10L)).getErrorCode());
    }

    @Test
    void missingAndInvalidSiteIdsAreRejected() {
        assertEquals(ErrorCode.INVALID_SITE_REFERENCE,
                assertThrows(BusinessException.class, () -> validator.requireExistingSite(null)).getErrorCode());
        assertEquals(ErrorCode.INVALID_SITE_REFERENCE,
                assertThrows(BusinessException.class, () -> validator.requireExistingSite(0L)).getErrorCode());
        verifyNoInteractions(client);

        when(client.findById(7L)).thenThrow(siteError(404));
        assertEquals(ErrorCode.INVALID_SITE_REFERENCE,
                assertThrows(BusinessException.class, () -> validator.requireExistingSite(7L)).getErrorCode());
    }

    @Test
    void unavailableOrMalformedSiteResponseFailsClosed() {
        when(client.findById(7L)).thenThrow(siteError(503));
        assertEquals(ErrorCode.SITE_REFERENCE_UNAVAILABLE,
                assertThrows(BusinessException.class, () -> validator.requireExistingSite(7L)).getErrorCode());

        doReturn(new SiteReferenceResponse(true,
                new SiteReferenceResponse.SiteIdentity(8L))).when(client).findById(7L);
        assertEquals(ErrorCode.SITE_REFERENCE_UNAVAILABLE,
                assertThrows(BusinessException.class, () -> validator.requireExistingSite(7L)).getErrorCode());

        doReturn(new SiteReferenceResponse(false, null)).when(client).findById(7L);
        assertEquals(ErrorCode.SITE_REFERENCE_UNAVAILABLE,
                assertThrows(BusinessException.class, () -> validator.requireExistingSite(7L)).getErrorCode());
    }

    private FeignException siteError(int status) {
        Request request = Request.create(Request.HttpMethod.GET, "/api/v1/sites/7",
                Map.of(), null, StandardCharsets.UTF_8, null);
        Response response = Response.builder().status(status).reason("Synthetic upstream failure")
                .request(request).build();
        return FeignException.errorStatus("SiteReferenceClient.findById", response);
    }
}
