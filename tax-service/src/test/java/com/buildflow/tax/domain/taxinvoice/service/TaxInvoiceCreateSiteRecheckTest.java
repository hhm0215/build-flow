package com.buildflow.tax.domain.taxinvoice.service;

import com.buildflow.tax.domain.taxinvoice.dto.TaxInvoiceCreateRequest;
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

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 현장 보관 경합: 저장·outbox 기록 뒤 커밋 직전 재확인이 실패하면 예외를 전파해 트랜잭션을 롤백시킨다. */
@ExtendWith(MockitoExtension.class)
class TaxInvoiceCreateSiteRecheckTest {

    @Mock TaxInvoiceRepository taxInvoiceRepository;
    @Mock KafkaProducerService kafkaProducerService;
    @Mock SiteReferenceValidator siteReferenceValidator;
    @Mock TaxPaymentService taxPaymentService;
    @Mock PurchaseEvidenceGuard purchaseEvidenceGuard;
    @InjectMocks TaxInvoiceService taxInvoiceService;

    @Test
    void createRechecksSiteAfterSaveAndOutboxBeforeCommit() {
        when(taxInvoiceRepository.save(any())).then(returnsFirstArg());

        taxInvoiceService.create(request());

        InOrder order = inOrder(siteReferenceValidator, taxInvoiceRepository, kafkaProducerService);
        order.verify(siteReferenceValidator).requireExistingSite(7L);
        order.verify(taxInvoiceRepository).save(any());
        order.verify(kafkaProducerService).sendTaxRegistered(any());
        order.verify(taxInvoiceRepository).flush();
        order.verify(siteReferenceValidator).recheckBeforeCommit(7L);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"SITE_ARCHIVED", "INVALID_SITE_REFERENCE", "SITE_REFERENCE_UNAVAILABLE"})
    void recheckFailureAfterSavePropagates(ErrorCode code) {
        when(taxInvoiceRepository.save(any())).then(returnsFirstArg());
        doThrow(new BusinessException(code)).when(siteReferenceValidator).recheckBeforeCommit(7L);

        BusinessException error = assertThrows(BusinessException.class, () -> taxInvoiceService.create(request()));

        assertEquals(code, error.getErrorCode());
        verify(taxInvoiceRepository).save(any());
        verify(kafkaProducerService).sendTaxRegistered(any());
    }

    private TaxInvoiceCreateRequest request() {
        TaxInvoiceCreateRequest request = mock(TaxInvoiceCreateRequest.class);
        when(request.getSiteId()).thenReturn(7L);
        when(request.getType()).thenReturn(TaxInvoiceType.SALES);
        when(request.getSupplyAmount()).thenReturn(new BigDecimal("100.00"));
        when(request.getTaxAmount()).thenReturn(new BigDecimal("10.00"));
        when(request.getIssueDate()).thenReturn(LocalDate.of(2026, 10, 28));
        return request;
    }
}
