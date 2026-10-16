package com.buildflow.tax.domain.taxinvoice.service;

import com.buildflow.tax.domain.taxinvoice.dto.OutstandingResponse;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoice;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoiceType;
import com.buildflow.tax.domain.taxinvoice.repository.TaxInvoiceRepository;
import com.buildflow.tax.global.kafka.KafkaProducerService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaxInvoiceOutstandingTest {

    private final TaxInvoiceRepository repository = mock(TaxInvoiceRepository.class);
    private final TaxInvoiceService service = new TaxInvoiceService(
            repository, mock(KafkaProducerService.class), mock(SiteReferenceValidator.class));

    private static TaxInvoice sales(String supply, String tax) {
        return TaxInvoice.builder()
                .siteId(7L)
                .type(TaxInvoiceType.SALES)
                .supplyAmount(new BigDecimal(supply))
                .taxAmount(new BigDecimal(tax))
                .issueDate(LocalDate.of(2026, 10, 1))
                .build();
    }

    @Test
    void 미수금은_매출_합계에서_입금_확인_금액을_뺀다() {
        TaxInvoice paid = sales("1000000", "100000");
        paid.confirmPayment(LocalDate.of(2026, 10, 10));
        TaxInvoice unpaid = sales("2000000", "200000");
        TaxInvoice unpaidCents = sales("0.50", "0.05");
        when(repository.findBySiteIdAndTypeOrderByCreatedAtDesc(7L, TaxInvoiceType.SALES))
                .thenReturn(List.of(paid, unpaid, unpaidCents));
        when(repository.findBySiteIdAndTypeAndPaymentConfirmedFalseOrderByIssueDateAsc(7L, TaxInvoiceType.SALES))
                .thenReturn(List.of(unpaid, unpaidCents));

        OutstandingResponse result = service.getOutstanding(7L);

        assertEquals(0, new BigDecimal("3300000.55").compareTo(result.getTotalSalesAmount()));
        assertEquals(0, new BigDecimal("1100000").compareTo(result.getConfirmedAmount()));
        assertEquals(0, new BigDecimal("2200000.55").compareTo(result.getOutstandingAmount()));
        assertEquals(2, result.getUnpaidCount());
        assertEquals(2, result.getUnpaidInvoices().size());
    }

    @Test
    void 매출이_없으면_모두_0이다() {
        when(repository.findBySiteIdAndTypeOrderByCreatedAtDesc(7L, TaxInvoiceType.SALES)).thenReturn(List.of());
        when(repository.findBySiteIdAndTypeAndPaymentConfirmedFalseOrderByIssueDateAsc(7L, TaxInvoiceType.SALES))
                .thenReturn(List.of());

        OutstandingResponse result = service.getOutstanding(7L);

        assertEquals(0, BigDecimal.ZERO.compareTo(result.getTotalSalesAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(result.getOutstandingAmount()));
        assertEquals(0, result.getUnpaidCount());
    }
}
