package com.buildflow.tax.domain.taxinvoice.service;

import com.buildflow.tax.domain.taxinvoice.dto.TaxInvoiceUpdateRequest;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoice;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoiceType;
import com.buildflow.tax.domain.taxinvoice.repository.TaxInvoiceRepository;
import com.buildflow.tax.global.kafka.KafkaProducerService;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Lock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaxInvoiceServiceLockTest {

    @Mock TaxInvoiceRepository taxInvoiceRepository;
    @Mock KafkaProducerService kafkaProducerService;
    @InjectMocks TaxInvoiceService taxInvoiceService;

    @Test
    void mutationLookupUsesPessimisticWriteLock() throws NoSuchMethodException {
        Lock lock = TaxInvoiceRepository.class
                .getMethod("findByIdForUpdate", Long.class)
                .getAnnotation(Lock.class);

        assertEquals(LockModeType.PESSIMISTIC_WRITE, lock.value());
    }

    @Test
    void updateAndDeleteReadLockedInvoice() {
        TaxInvoice invoice = invoice();
        when(taxInvoiceRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(invoice));
        TaxInvoiceUpdateRequest request = mock(TaxInvoiceUpdateRequest.class);
        when(request.getType()).thenReturn(TaxInvoiceType.SALES);
        when(request.getSupplyAmount()).thenReturn(new BigDecimal("200.00"));
        when(request.getTaxAmount()).thenReturn(new BigDecimal("20.00"));
        when(request.getIssueDate()).thenReturn(LocalDate.of(2026, 9, 21));

        taxInvoiceService.update(7L, request);
        taxInvoiceService.delete(7L);

        verify(taxInvoiceRepository, never()).findById(7L);
        verify(taxInvoiceRepository).delete(invoice);
    }

    private TaxInvoice invoice() {
        return TaxInvoice.builder()
                .siteId(3L)
                .type(TaxInvoiceType.SALES)
                .supplyAmount(new BigDecimal("100.00"))
                .taxAmount(new BigDecimal("10.00"))
                .counterparty("거래처")
                .issueDate(LocalDate.of(2026, 9, 20))
                .build();
    }
}
