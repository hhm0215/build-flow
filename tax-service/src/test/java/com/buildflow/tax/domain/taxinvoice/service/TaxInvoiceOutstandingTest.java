package com.buildflow.tax.domain.taxinvoice.service;

import com.buildflow.tax.domain.taxinvoice.dto.OutstandingResponse;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoice;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoiceType;
import com.buildflow.tax.domain.taxinvoice.entity.TaxPaymentStatus;
import com.buildflow.tax.domain.taxinvoice.repository.TaxInvoiceRepository;
import com.buildflow.tax.global.kafka.KafkaProducerService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaxInvoiceOutstandingTest {

    private final TaxInvoiceRepository repository = mock(TaxInvoiceRepository.class);
    private final TaxPaymentService paymentService = mock(TaxPaymentService.class);
    private final TaxInvoiceService service = new TaxInvoiceService(
            repository, mock(KafkaProducerService.class), mock(SiteReferenceValidator.class), paymentService);

    private static TaxInvoice sales(long id, String supply, String tax, LocalDate issueDate) {
        TaxInvoice invoice = TaxInvoice.builder()
                .siteId(7L)
                .type(TaxInvoiceType.SALES)
                .supplyAmount(new BigDecimal(supply))
                .taxAmount(new BigDecimal(tax))
                .issueDate(issueDate)
                .build();
        ReflectionTestUtils.setField(invoice, "id", id);
        return invoice;
    }

    @Test
    void 미수금은_매출_합계에서_유효_입금_합계를_뺀다() {
        TaxInvoice paid = sales(1L, "1000000", "100000", LocalDate.of(2026, 10, 1));
        paid.applyPaymentTotal(new BigDecimal("1100000.00"), LocalDate.of(2026, 10, 10));
        TaxInvoice partial = sales(2L, "2000000", "200000", LocalDate.of(2026, 10, 3));
        TaxInvoice unpaidCents = sales(3L, "0.50", "0.05", null);
        when(repository.findBySiteIdAndTypeOrderByCreatedAtDesc(7L, TaxInvoiceType.SALES))
                .thenReturn(List.of(paid, partial, unpaidCents));
        when(paymentService.paidAmounts(anyCollection())).thenReturn(Map.of(
                1L, new BigDecimal("1100000.00"),
                2L, new BigDecimal("500000.00"),
                3L, BigDecimal.ZERO));

        OutstandingResponse result = service.getOutstanding(7L);

        assertEquals(0, new BigDecimal("3300000.55").compareTo(result.getTotalSalesAmount()));
        assertEquals(0, new BigDecimal("1600000").compareTo(result.getConfirmedAmount()));
        assertEquals(0, new BigDecimal("1700000.55").compareTo(result.getOutstandingAmount()));
        assertEquals(2, result.getUnpaidCount());
        // 발행일 오름차순(null 먼저) — 기존 정렬 유지
        assertEquals(3L, result.getUnpaidInvoices().get(0).getId());
        assertEquals(2L, result.getUnpaidInvoices().get(1).getId());
        assertEquals(TaxPaymentStatus.PARTIAL, result.getUnpaidInvoices().get(1).getPaymentStatus());
        assertEquals(0, new BigDecimal("1700000.00")
                .compareTo(result.getUnpaidInvoices().get(1).getRemainingAmount()));
    }

    @Test
    void 매출이_없으면_모두_0이다() {
        when(repository.findBySiteIdAndTypeOrderByCreatedAtDesc(7L, TaxInvoiceType.SALES)).thenReturn(List.of());
        when(paymentService.paidAmounts(anyCollection())).thenReturn(Map.of());

        OutstandingResponse result = service.getOutstanding(7L);

        assertEquals(0, BigDecimal.ZERO.compareTo(result.getTotalSalesAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(result.getOutstandingAmount()));
        assertEquals(0, result.getUnpaidCount());
    }
}
