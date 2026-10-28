package com.buildflow.tax.domain.taxinvoice.service;

import com.buildflow.tax.domain.taxinvoice.dto.TaxInvoiceUpdateRequest;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoice;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoiceType;
import com.buildflow.tax.domain.taxinvoice.repository.TaxInvoiceRepository;
import com.buildflow.tax.global.exception.BusinessException;
import com.buildflow.tax.global.exception.ErrorCode;
import com.buildflow.tax.global.kafka.KafkaProducerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 현장 보관 경합: 기존 세금계산서 수정·삭제는 변경 flush 뒤 커밋 직전에 보관 여부를 다시 확인한다. */
@ExtendWith(MockitoExtension.class)
class TaxInvoiceMutationSiteRecheckTest {

    @Mock TaxInvoiceRepository taxInvoiceRepository;
    @Mock KafkaProducerService kafkaProducerService;
    @Mock SiteReferenceValidator siteReferenceValidator;
    @Mock TaxPaymentService taxPaymentService;
    @Mock PurchaseEvidenceGuard purchaseEvidenceGuard;
    @InjectMocks TaxInvoiceService taxInvoiceService;

    @Test
    void updateRechecksAfterFlush() {
        when(taxInvoiceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(invoice()));
        when(taxPaymentService.activePaidAmount(any())).thenReturn(BigDecimal.ZERO);

        taxInvoiceService.update(1L, updateRequest());

        InOrder order = inOrder(siteReferenceValidator, taxInvoiceRepository);
        order.verify(siteReferenceValidator).requireSiteNotArchived(7L);
        order.verify(taxInvoiceRepository).flush();
        order.verify(siteReferenceValidator).recheckNotArchivedBeforeCommit(7L);
    }

    @Test
    void deleteRechecksAfterLedgerDeleteAndFlush() {
        TaxInvoice invoice = invoice();
        when(taxInvoiceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(invoice));
        when(taxPaymentService.activePaidAmount(any())).thenReturn(BigDecimal.ZERO);

        taxInvoiceService.delete(1L);

        InOrder order = inOrder(siteReferenceValidator, taxInvoiceRepository, taxPaymentService);
        order.verify(siteReferenceValidator).requireSiteNotArchived(7L);
        order.verify(taxPaymentService).deleteLedger(any());
        order.verify(taxInvoiceRepository).delete(invoice);
        order.verify(taxInvoiceRepository).flush();
        order.verify(siteReferenceValidator).recheckNotArchivedBeforeCommit(7L);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"SITE_ARCHIVED_DOCUMENT_LOCKED", "SITE_REFERENCE_UNAVAILABLE"})
    void recheckFailurePropagates(ErrorCode code) {
        when(taxInvoiceRepository.findByIdForUpdate(1L)).thenAnswer(invocation -> Optional.of(invoice()));
        when(taxPaymentService.activePaidAmount(any())).thenReturn(BigDecimal.ZERO);
        doThrow(new BusinessException(code)).when(siteReferenceValidator).recheckNotArchivedBeforeCommit(7L);

        assertEquals(code, assertThrows(BusinessException.class,
                () -> taxInvoiceService.update(1L, updateRequest())).getErrorCode());
        assertEquals(code, assertThrows(BusinessException.class,
                () -> taxInvoiceService.delete(1L)).getErrorCode());
        verify(siteReferenceValidator, times(2)).requireSiteNotArchived(7L);
    }

    private TaxInvoice invoice() {
        return TaxInvoice.builder()
                .siteId(7L)
                .type(TaxInvoiceType.SALES)
                .supplyAmount(new BigDecimal("100.00"))
                .taxAmount(new BigDecimal("10.00"))
                .counterparty("거래처")
                .issueDate(LocalDate.of(2026, 10, 28))
                .build();
    }

    private TaxInvoiceUpdateRequest updateRequest() {
        TaxInvoiceUpdateRequest request = new TaxInvoiceUpdateRequest();
        ReflectionTestUtils.setField(request, "type", TaxInvoiceType.SALES);
        ReflectionTestUtils.setField(request, "supplyAmount", new BigDecimal("200.00"));
        ReflectionTestUtils.setField(request, "taxAmount", new BigDecimal("20.00"));
        ReflectionTestUtils.setField(request, "counterparty", "수정 거래처");
        ReflectionTestUtils.setField(request, "issueDate", LocalDate.of(2026, 10, 28));
        return request;
    }
}
