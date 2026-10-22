package com.buildflow.purchase.domain.purchase.service;

import com.buildflow.purchase.domain.purchase.dto.PurchaseEvidenceLinkRequest;
import com.buildflow.purchase.domain.purchase.dto.PurchaseEvidenceSummaryResponse;
import com.buildflow.purchase.domain.purchase.dto.PurchaseResponse;
import com.buildflow.purchase.domain.purchase.entity.Purchase;
import com.buildflow.purchase.domain.purchase.repository.PurchaseRepository;
import com.buildflow.purchase.global.exception.BusinessException;
import com.buildflow.purchase.global.exception.ErrorCode;
import com.buildflow.purchase.infra.feign.TaxInvoiceReference;
import com.buildflow.purchase.infra.feign.TaxInvoiceReferenceClient;
import com.buildflow.purchase.infra.feign.TaxInvoiceReferenceListResponse;
import com.buildflow.purchase.infra.feign.TaxInvoiceReferenceResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PurchaseEvidenceServiceTest {

    private final PurchaseRepository repository = mock(PurchaseRepository.class);
    private final SiteReferenceValidator siteValidator = mock(SiteReferenceValidator.class);
    private final TaxInvoiceReferenceClient taxClient = mock(TaxInvoiceReferenceClient.class);
    private final PurchaseEvidenceService service = new PurchaseEvidenceService(
            repository, siteValidator, new TaxInvoiceReferenceReader(taxClient));

    @Test
    void linksPurchaseInvoiceOfSameSiteWithoutProfitEvent() {
        Purchase purchase = purchase(1L, 3L, "1000.00");
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(purchase));
        when(taxClient.findById(20L)).thenReturn(found(invoice(20L, 3L, "PURCHASE", "1000.00", "100.00")));

        PurchaseResponse response = service.link(1L, new PurchaseEvidenceLinkRequest(20L));

        assertEquals(20L, response.getEvidenceTaxInvoiceId());
        assertEquals(1L, purchase.getEventRevision());
        verify(siteValidator).requireSiteNotArchived(3L);
    }

    @Test
    void salesInvoiceOtherSiteAndMissingInvoiceAreRejectedWith400() {
        Purchase purchase = purchase(1L, 3L, "1000.00");
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(purchase));

        when(taxClient.findById(21L)).thenReturn(found(invoice(21L, 3L, "SALES", "1000.00", "100.00")));
        assertError(ErrorCode.EVIDENCE_TAX_INVOICE_NOT_PURCHASE, () -> service.link(1L, new PurchaseEvidenceLinkRequest(21L)));

        when(taxClient.findById(22L)).thenReturn(found(invoice(22L, 4L, "PURCHASE", "1000.00", "100.00")));
        assertError(ErrorCode.EVIDENCE_TAX_INVOICE_SITE_MISMATCH, () -> service.link(1L, new PurchaseEvidenceLinkRequest(22L)));

        when(taxClient.findById(23L)).thenThrow(feignError(404));
        assertError(ErrorCode.EVIDENCE_TAX_INVOICE_NOT_FOUND, () -> service.link(1L, new PurchaseEvidenceLinkRequest(23L)));

        assertEquals(400, ErrorCode.EVIDENCE_TAX_INVOICE_NOT_PURCHASE.getStatus().value());
        assertEquals(400, ErrorCode.EVIDENCE_TAX_INVOICE_SITE_MISMATCH.getStatus().value());
        assertEquals(400, ErrorCode.EVIDENCE_TAX_INVOICE_NOT_FOUND.getStatus().value());
        assertNull(purchase.getEvidenceTaxInvoiceId());
    }

    @Test
    void taxServiceFailureOrMalformedResponseFailsClosedWith503() {
        Purchase purchase = purchase(1L, 3L, "1000.00");
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(purchase));

        when(taxClient.findById(20L)).thenThrow(feignError(503));
        assertError(ErrorCode.TAX_INVOICE_REFERENCE_UNAVAILABLE, () -> service.link(1L, new PurchaseEvidenceLinkRequest(20L)));

        when(taxClient.findById(24L)).thenReturn(found(invoice(99L, 3L, "PURCHASE", "1.00", "0.00")));
        assertError(ErrorCode.TAX_INVOICE_REFERENCE_UNAVAILABLE, () -> service.link(1L, new PurchaseEvidenceLinkRequest(24L)));

        when(taxClient.findBySite(3L, "PURCHASE")).thenThrow(feignError(500));
        assertError(ErrorCode.TAX_INVOICE_REFERENCE_UNAVAILABLE, () -> service.summarize(3L));

        assertEquals(503, ErrorCode.TAX_INVOICE_REFERENCE_UNAVAILABLE.getStatus().value());
        assertNull(purchase.getEvidenceTaxInvoiceId());
    }

    @Test
    void archivedSiteBlocksLinkAndUnlinkWith409BeforeTaxLookup() {
        Purchase purchase = purchase(1L, 3L, "1000.00");
        purchase.linkEvidence(20L);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(purchase));
        doThrow(new BusinessException(ErrorCode.SITE_ARCHIVED_DOCUMENT_LOCKED))
                .when(siteValidator).requireSiteNotArchived(3L);

        assertError(ErrorCode.SITE_ARCHIVED_DOCUMENT_LOCKED, () -> service.link(1L, new PurchaseEvidenceLinkRequest(21L)));
        assertError(ErrorCode.SITE_ARCHIVED_DOCUMENT_LOCKED, () -> service.unlink(1L));

        assertEquals(409, ErrorCode.SITE_ARCHIVED_DOCUMENT_LOCKED.getStatus().value());
        assertEquals(20L, purchase.getEvidenceTaxInvoiceId());
        verifyNoInteractions(taxClient);
    }

    @Test
    void unlinkClearsEvidenceWithoutTaxLookup() {
        Purchase purchase = purchase(1L, 3L, "1000.00");
        purchase.linkEvidence(20L);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(purchase));

        assertNull(service.unlink(1L).getEvidenceTaxInvoiceId());
        verifyNoInteractions(taxClient);
    }

    @Test
    void missingPurchaseIs404() {
        when(repository.findByIdForUpdate(anyLong())).thenReturn(Optional.empty());
        assertError(ErrorCode.PURCHASE_NOT_FOUND, () -> service.link(9L, new PurchaseEvidenceLinkRequest(20L)));
        verify(siteValidator, never()).requireSiteNotArchived(any());
    }

    @Test
    void summaryReportsUnlinkedBrokenAndDifferences() {
        Purchase linkedA = purchase(1L, 3L, "600.00");
        linkedA.linkEvidence(20L);
        Purchase linkedB = purchase(2L, 3L, "300.00");
        linkedB.linkEvidence(20L);
        Purchase unlinked = purchase(3L, 3L, "250.00");
        Purchase broken = purchase(4L, 3L, "50.00");
        broken.linkEvidence(77L);
        when(repository.findBySiteIdOrderByCreatedAtDesc(3L)).thenReturn(List.of(linkedA, linkedB, unlinked, broken));
        when(taxClient.findBySite(3L, "PURCHASE")).thenReturn(new TaxInvoiceReferenceListResponse(true, List.of(
                invoice(20L, 3L, "PURCHASE", "1000.00", "100.00"),
                invoice(21L, 3L, "PURCHASE", "400.00", "40.00"),
                invoice(30L, 9L, "PURCHASE", "999.00", "0.00"),
                invoice(31L, 3L, "SALES", "999.00", "0.00"))));

        PurchaseEvidenceSummaryResponse summary = service.summarize(3L);

        assertEquals(4, summary.getPurchaseCount());
        assertAmount("1200.00", summary.getPurchaseAmount());
        assertEquals(2, summary.getLinkedPurchaseCount());
        assertAmount("900.00", summary.getLinkedPurchaseAmount());
        assertEquals(2, summary.getUnlinkedPurchaseCount());
        assertAmount("300.00", summary.getUnlinkedPurchaseAmount());
        assertEquals(1, summary.getBrokenLinkCount());
        assertEquals(2, summary.getInvoiceCount());
        assertAmount("1400.00", summary.getInvoiceSupplyAmount());
        assertAmount("1540.00", summary.getInvoiceTotalAmount());
        assertEquals(1, summary.getInvoiceWithoutPurchaseCount());
        assertAmount("500.00", summary.getSupplyDifference());
        assertAmount("640.00", summary.getTotalDifference());
        PurchaseEvidenceSummaryResponse.InvoiceEvidence first = summary.getInvoices().get(0);
        assertEquals(20L, first.getTaxInvoiceId());
        assertEquals(2, first.getLinkedPurchaseCount());
        assertAmount("100.00", first.getSupplyDifference());
        assertAmount("200.00", first.getTotalDifference());
    }

    @Test
    void summaryRejectsInvalidSiteIdAndDecodesTaxServiceShape() throws Exception {
        assertError(ErrorCode.INVALID_SITE_REFERENCE, () -> service.summarize(0L));

        String json = """
                {"success":true,"data":{"id":20,"siteId":3,"type":"PURCHASE","supplyAmount":1000.00,
                "taxAmount":100.00,"totalAmount":1100.00,"counterparty":"합성 공급처","issueDate":"2026-10-01",
                "paymentConfirmed":false,"paidAmount":null,"paymentStatus":null},"error":null}
                """;
        TaxInvoiceReferenceResponse decoded = new ObjectMapper().findAndRegisterModules()
                .readValue(json, TaxInvoiceReferenceResponse.class);
        assertEquals(LocalDate.of(2026, 10, 1), decoded.data().issueDate());
        assertEquals(true, decoded.data().isPurchase());
    }

    @Test
    void countLinksDelegatesToRepository() {
        when(repository.countByEvidenceTaxInvoiceId(20L)).thenReturn(2L);
        assertEquals(2L, service.countLinks(20L).linkedPurchaseCount());
    }

    private static void assertError(ErrorCode expected, org.junit.jupiter.api.function.Executable executable) {
        assertEquals(expected, assertThrows(BusinessException.class, executable).getErrorCode());
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> expected + " != " + actual);
    }

    private static Purchase purchase(Long id, Long siteId, String amount) {
        Purchase purchase = Purchase.builder()
                .siteId(siteId)
                .itemName("자재")
                .quantity(1)
                .unitPrice(new BigDecimal(amount))
                .build();
        ReflectionTestUtils.setField(purchase, "id", id);
        return purchase;
    }

    private static TaxInvoiceReference invoice(Long id, Long siteId, String type, String supply, String tax) {
        BigDecimal supplyAmount = new BigDecimal(supply);
        BigDecimal taxAmount = new BigDecimal(tax);
        return new TaxInvoiceReference(id, siteId, type, supplyAmount, taxAmount, supplyAmount.add(taxAmount),
                "합성 공급처", LocalDate.of(2026, 10, 1));
    }

    private static TaxInvoiceReferenceResponse found(TaxInvoiceReference invoice) {
        return new TaxInvoiceReferenceResponse(true, invoice);
    }

    private static FeignException feignError(int status) {
        Request request = Request.create(Request.HttpMethod.GET, "/api/v1/taxes/20",
                Map.of(), null, StandardCharsets.UTF_8, null);
        Response response = Response.builder().status(status).reason("Synthetic upstream failure")
                .request(request).build();
        return FeignException.errorStatus("TaxInvoiceReferenceClient.findById", response);
    }
}
