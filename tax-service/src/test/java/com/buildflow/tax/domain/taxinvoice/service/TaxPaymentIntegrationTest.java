package com.buildflow.tax.domain.taxinvoice.service;

import com.buildflow.tax.domain.taxinvoice.dto.TaxInvoiceCreateRequest;
import com.buildflow.tax.domain.taxinvoice.dto.TaxPaymentCreateRequest;
import com.buildflow.tax.domain.taxinvoice.dto.TaxPaymentResponse;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoiceType;
import com.buildflow.tax.domain.taxinvoice.entity.TaxPaymentStatus;
import com.buildflow.tax.domain.taxinvoice.repository.TaxInvoiceRepository;
import com.buildflow.tax.domain.taxinvoice.repository.TaxPaymentRepository;
import com.buildflow.tax.global.config.JpaAuditingConfig;
import com.buildflow.tax.global.exception.BusinessException;
import com.buildflow.tax.global.exception.ErrorCode;
import com.buildflow.tax.global.kafka.KafkaProducerService;
import com.buildflow.tax.global.outbox.OutboxEventRepository;
import com.buildflow.tax.global.outbox.OutboxWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** H2(MySQL 모드)에서 실제 잠금·합계 쿼리로 분할 입금 경합과 집계를 검증한다. */
@DataJpaTest
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, TaxInvoiceService.class, TaxPaymentService.class, KafkaProducerService.class,
        OutboxWriter.class, TaxPaymentIntegrationTest.JsonConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class TaxPaymentIntegrationTest {

    private final TaxInvoiceService taxService;
    private final TaxPaymentService paymentService;
    private final TaxInvoiceRepository taxRepository;
    private final TaxPaymentRepository paymentRepository;
    private final OutboxEventRepository outboxRepository;

    @MockBean private SiteReferenceValidator siteReferenceValidator;
    @MockBean private PurchaseEvidenceGuard purchaseEvidenceGuard;

    TaxPaymentIntegrationTest(TaxInvoiceService taxService, TaxPaymentService paymentService,
                              TaxInvoiceRepository taxRepository, TaxPaymentRepository paymentRepository,
                              OutboxEventRepository outboxRepository) {
        this.taxService = taxService;
        this.paymentService = paymentService;
        this.taxRepository = taxRepository;
        this.paymentRepository = paymentRepository;
        this.outboxRepository = outboxRepository;
    }

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        paymentRepository.deleteAll();
        taxRepository.deleteAll();
    }

    @Test
    void concurrentPartialPaymentsNeverExceedTotal() throws Exception {
        long id = taxService.create(createRequest(TaxInvoiceType.SALES)).getId(); // 총액 110.00
        int threads = 4;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        paymentService.record(id, paymentRequest("50.00"));
                        return true;
                    } catch (BusinessException exceeded) {
                        assertThat(exceeded.getErrorCode()).isEqualTo(ErrorCode.TAX_PAYMENT_EXCEEDS_BALANCE);
                        return false;
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int succeeded = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(10, TimeUnit.SECONDS)) succeeded++;
            }
            assertThat(succeeded).isEqualTo(2);
            assertThat(paymentRepository.sumActiveAmount(id)).isEqualByComparingTo("100.00");
            assertThat(taxRepository.findById(id).orElseThrow().isPaymentConfirmed()).isFalse();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void partialPaymentsCancelAndOutstandingUseActiveSum() {
        long id = taxService.create(createRequest(TaxInvoiceType.SALES)).getId();
        long purchaseId = taxService.create(createRequest(TaxInvoiceType.PURCHASE)).getId();
        TaxPaymentResponse first = paymentService.record(id, paymentRequest("60.00"));
        paymentService.record(id, paymentRequest("50.00"));

        var completed = taxService.findById(id);
        assertThat(completed.getPaymentStatus()).isEqualTo(TaxPaymentStatus.PAID);
        assertThat(completed.isPaymentConfirmed()).isTrue();
        assertThat(outboxRepository.findAll().stream()
                .filter(event -> event.getTopic().equals("tax.payment.confirmed")).count()).isEqualTo(1);
        assertThatThrownBy(() -> taxService.update(id, null))
                .isInstanceOf(BusinessException.class);

        paymentService.cancel(id, first.getId(), null);

        var list = taxService.findAll(7L, null);
        var sales = list.stream().filter(item -> item.getId().equals(id)).findFirst().orElseThrow();
        var purchase = list.stream().filter(item -> item.getId().equals(purchaseId)).findFirst().orElseThrow();
        assertThat(sales.getPaidAmount()).isEqualByComparingTo("50.00");
        assertThat(sales.getRemainingAmount()).isEqualByComparingTo("60.00");
        assertThat(sales.getPaymentStatus()).isEqualTo(TaxPaymentStatus.PARTIAL);
        assertThat(sales.isPaymentConfirmed()).isFalse();
        assertThat(purchase.getPaidAmount()).isNull();
        assertThat(purchase.getPaymentStatus()).isNull();

        var outstanding = taxService.getOutstanding(7L);
        assertThat(outstanding.getTotalSalesAmount()).isEqualByComparingTo("110.00");
        assertThat(outstanding.getConfirmedAmount()).isEqualByComparingTo("50.00");
        assertThat(outstanding.getOutstandingAmount()).isEqualByComparingTo("60.00");
        assertThat(outstanding.getUnpaidCount()).isEqualTo(1);

        var history = paymentService.findPayments(id);
        assertThat(history).hasSize(2);
        assertThat(history.stream().filter(TaxPaymentResponse::isCanceled).count()).isEqualTo(1);
    }

    @Test
    void invoiceWithOnlyCanceledPaymentsCanBeDeletedWithLedger() {
        long id = taxService.create(createRequest(TaxInvoiceType.SALES)).getId();
        TaxPaymentResponse payment = paymentService.record(id, paymentRequest("10.00"));
        assertThatThrownBy(() -> taxService.delete(id))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_CONFIRMED_TAX_INVOICE_IMMUTABLE));

        paymentService.cancel(id, payment.getId(), null);
        taxService.delete(id);

        assertThat(taxRepository.existsById(id)).isFalse();
        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    void siteArchivedBeforeCancelCommitRestoresPaymentAndInvoiceState() {
        long id = taxService.create(createRequest(TaxInvoiceType.SALES)).getId();
        TaxPaymentResponse payment = paymentService.record(id, paymentRequest("110.00"));
        assertThat(taxRepository.findById(id).orElseThrow().isPaymentConfirmed()).isTrue();
        long outboxBefore = outboxRepository.count();
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.SITE_ARCHIVED_DOCUMENT_LOCKED))
                .when(siteReferenceValidator).recheckNotArchivedBeforeCommit(7L);

        assertThatThrownBy(() -> paymentService.cancel(id, payment.getId(), null))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SITE_ARCHIVED_DOCUMENT_LOCKED));

        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().isCanceled()).isFalse();
        assertThat(paymentRepository.sumActiveAmount(id)).isEqualByComparingTo("110.00");
        var invoice = taxRepository.findById(id).orElseThrow();
        assertThat(invoice.isPaymentConfirmed()).isTrue();
        assertThat(invoice.getPaymentDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(outboxRepository.count()).isEqualTo(outboxBefore);
    }

    @Test
    void legacyConfirmedInvoiceIsBackfilledOnce() {
        long id = taxService.create(createRequest(TaxInvoiceType.SALES)).getId();
        var invoice = taxRepository.findById(id).orElseThrow();
        ReflectionTestUtils.setField(invoice, "paymentConfirmed", true);
        ReflectionTestUtils.setField(invoice, "paymentDate", LocalDate.of(2026, 9, 1));
        taxRepository.save(invoice);

        assertThat(taxService.findById(id).getPaidAmount()).isEqualByComparingTo("110.00");
        assertThat(paymentService.backfillLegacyConfirmed()).isEqualTo(1);
        assertThat(paymentService.backfillLegacyConfirmed()).isZero();
        assertThat(paymentRepository.sumActiveAmount(id)).isEqualByComparingTo("110.00");
        assertThat(taxService.getOutstanding(7L).getOutstandingAmount()).isEqualByComparingTo("0");
    }

    private TaxInvoiceCreateRequest createRequest(TaxInvoiceType type) {
        TaxInvoiceCreateRequest request = new TaxInvoiceCreateRequest();
        ReflectionTestUtils.setField(request, "siteId", 7L);
        ReflectionTestUtils.setField(request, "type", type);
        ReflectionTestUtils.setField(request, "supplyAmount", new BigDecimal("100.00"));
        ReflectionTestUtils.setField(request, "taxAmount", new BigDecimal("10.00"));
        ReflectionTestUtils.setField(request, "counterparty", "거래처");
        ReflectionTestUtils.setField(request, "issueDate", LocalDate.of(2026, 9, 20));
        return request;
    }

    private TaxPaymentCreateRequest paymentRequest(String amount) {
        TaxPaymentCreateRequest request = new TaxPaymentCreateRequest();
        ReflectionTestUtils.setField(request, "amount", new BigDecimal(amount));
        ReflectionTestUtils.setField(request, "paymentDate", LocalDate.of(2026, 10, 1));
        return request;
    }

    @TestConfiguration
    static class JsonConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
