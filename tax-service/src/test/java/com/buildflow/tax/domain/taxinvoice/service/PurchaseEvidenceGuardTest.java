package com.buildflow.tax.domain.taxinvoice.service;

import com.buildflow.tax.domain.taxinvoice.dto.TaxInvoiceUpdateRequest;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoice;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoiceType;
import com.buildflow.tax.domain.taxinvoice.repository.TaxInvoiceRepository;
import com.buildflow.tax.global.exception.BusinessException;
import com.buildflow.tax.global.exception.ErrorCode;
import com.buildflow.tax.global.kafka.KafkaProducerService;
import com.buildflow.tax.infra.feign.PurchaseEvidenceClient;
import com.buildflow.tax.infra.feign.PurchaseEvidenceLinkResponse;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PurchaseEvidenceGuardTest {

    private final TaxInvoiceRepository repository = mock(TaxInvoiceRepository.class);
    private final TaxPaymentService paymentService = mock(TaxPaymentService.class);
    private final PurchaseEvidenceClient client = mock(PurchaseEvidenceClient.class);
    private final TaxInvoiceService service = new TaxInvoiceService(repository, mock(KafkaProducerService.class),
            mock(SiteReferenceValidator.class), paymentService, new PurchaseEvidenceGuard(client));

    @Test
    void linkedPurchaseInvoiceCannotBeDeletedOrTurnedIntoSales() {
        TaxInvoice invoice = invoice(7L, TaxInvoiceType.PURCHASE);
        when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(invoice));
        when(paymentService.activePaidAmount(any())).thenReturn(BigDecimal.ZERO);
        when(client.countLinks(7L)).thenReturn(links(7L, 2L));

        assertError(ErrorCode.TAX_INVOICE_LINKED_TO_PURCHASES, () -> service.delete(7L));
        assertError(ErrorCode.TAX_INVOICE_LINKED_TO_PURCHASES, () -> service.update(7L, update(TaxInvoiceType.SALES)));

        assertEquals(409, ErrorCode.TAX_INVOICE_LINKED_TO_PURCHASES.getStatus().value());
        assertEquals(TaxInvoiceType.PURCHASE, invoice.getType());
        verify(repository, never()).delete(any());
    }

    @Test
    void unlinkedPurchaseInvoiceCanBeDeleted() {
        TaxInvoice invoice = invoice(7L, TaxInvoiceType.PURCHASE);
        when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(invoice));
        when(paymentService.activePaidAmount(any())).thenReturn(BigDecimal.ZERO);
        when(client.countLinks(7L)).thenReturn(links(7L, 0L));

        service.delete(7L);

        verify(repository).delete(invoice);
    }

    @Test
    void purchaseServiceFailureOrMalformedResponseFailsClosedWith503() {
        TaxInvoice invoice = invoice(7L, TaxInvoiceType.PURCHASE);
        when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(invoice));
        when(paymentService.activePaidAmount(any())).thenReturn(BigDecimal.ZERO);

        when(client.countLinks(7L)).thenThrow(feignError(503));
        assertError(ErrorCode.PURCHASE_REFERENCE_UNAVAILABLE, () -> service.delete(7L));

        doReturn(links(8L, 0L)).when(client).countLinks(7L);
        assertError(ErrorCode.PURCHASE_REFERENCE_UNAVAILABLE, () -> service.delete(7L));

        doReturn(new PurchaseEvidenceLinkResponse(false, null)).when(client).countLinks(7L);
        assertError(ErrorCode.PURCHASE_REFERENCE_UNAVAILABLE, () -> service.delete(7L));

        assertEquals(503, ErrorCode.PURCHASE_REFERENCE_UNAVAILABLE.getStatus().value());
        verify(repository, never()).delete(any());
    }

    @Test
    void salesInvoicesAndPurchaseAmountEditsSkipEvidenceLookup() {
        TaxInvoice sales = invoice(7L, TaxInvoiceType.SALES);
        TaxInvoice purchase = invoice(8L, TaxInvoiceType.PURCHASE);
        TaxInvoice salesToDelete = invoice(9L, TaxInvoiceType.SALES);
        when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(sales));
        when(repository.findByIdForUpdate(8L)).thenReturn(Optional.of(purchase));
        when(repository.findByIdForUpdate(9L)).thenReturn(Optional.of(salesToDelete));
        when(paymentService.activePaidAmount(any())).thenReturn(BigDecimal.ZERO);

        service.update(7L, update(TaxInvoiceType.PURCHASE));
        service.update(8L, update(TaxInvoiceType.PURCHASE));
        service.delete(9L);

        verifyNoInteractions(client);
    }

    private static void assertError(ErrorCode expected, org.junit.jupiter.api.function.Executable executable) {
        assertEquals(expected, assertThrows(BusinessException.class, executable).getErrorCode());
    }

    private static TaxInvoiceUpdateRequest update(TaxInvoiceType type) {
        TaxInvoiceUpdateRequest request = mock(TaxInvoiceUpdateRequest.class);
        when(request.getType()).thenReturn(type);
        when(request.getSupplyAmount()).thenReturn(new BigDecimal("200.00"));
        when(request.getTaxAmount()).thenReturn(new BigDecimal("20.00"));
        return request;
    }

    private static PurchaseEvidenceLinkResponse links(Long taxInvoiceId, Long count) {
        return new PurchaseEvidenceLinkResponse(true, new PurchaseEvidenceLinkResponse.LinkCount(taxInvoiceId, count));
    }

    private static TaxInvoice invoice(Long id, TaxInvoiceType type) {
        TaxInvoice invoice = TaxInvoice.builder()
                .siteId(3L)
                .type(type)
                .supplyAmount(new BigDecimal("100.00"))
                .taxAmount(new BigDecimal("10.00"))
                .build();
        ReflectionTestUtils.setField(invoice, "id", id);
        return invoice;
    }

    private static FeignException feignError(int status) {
        Request request = Request.create(Request.HttpMethod.GET, "/api/v1/purchases/evidence-links",
                Map.of(), null, StandardCharsets.UTF_8, null);
        Response response = Response.builder().status(status).reason("Synthetic upstream failure")
                .request(request).build();
        return FeignException.errorStatus("PurchaseEvidenceClient.countLinks", response);
    }
}
