package com.buildflow.tax.global.outbox;

import com.buildflow.tax.domain.taxinvoice.dto.PaymentConfirmRequest;
import com.buildflow.tax.domain.taxinvoice.dto.TaxInvoiceCreateRequest;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoiceType;
import com.buildflow.tax.domain.taxinvoice.repository.TaxInvoiceRepository;
import com.buildflow.tax.domain.taxinvoice.service.TaxInvoiceService;
import com.buildflow.tax.global.config.JpaAuditingConfig;
import com.buildflow.tax.global.exception.BusinessException;
import com.buildflow.tax.global.kafka.KafkaProducerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, TaxInvoiceService.class, KafkaProducerService.class,
        OutboxWriter.class, OutboxClaimService.class, TaxOutboxIntegrationTest.JsonConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class TaxOutboxIntegrationTest {

    private final TaxInvoiceService taxService;
    private final TaxInvoiceRepository taxRepository;
    private final OutboxEventRepository outboxRepository;
    private final OutboxClaimService claimService;
    private final OutboxWriter outboxWriter;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper objectMapper;

    TaxOutboxIntegrationTest(TaxInvoiceService taxService, TaxInvoiceRepository taxRepository,
                             OutboxEventRepository outboxRepository, OutboxClaimService claimService,
                             OutboxWriter outboxWriter, PlatformTransactionManager transactionManager,
                             ObjectMapper objectMapper) {
        this.taxService = taxService;
        this.taxRepository = taxRepository;
        this.outboxRepository = outboxRepository;
        this.claimService = claimService;
        this.outboxWriter = outboxWriter;
        this.transactionManager = transactionManager;
        this.objectMapper = objectMapper;
    }

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        taxRepository.deleteAll();
    }

    @Test
    void registrationAndPaymentKeepEnvelopeAndRecordKey() throws Exception {
        long id = taxService.create(createRequest()).getId();
        OutboxEvent registered = outboxRepository.findAll().get(0);

        assertThat(registered.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(registered.getTopic()).isEqualTo("tax.registered");
        assertThat(registered.getRecordKey()).isEqualTo(String.valueOf(id));
        assertThat(UUID.fromString(registered.getEventId()).toString()).isEqualTo(registered.getEventId());
        var registeredJson = objectMapper.readTree(registered.getPayloadJson());
        assertThat(registeredJson.get("eventId").asText()).isEqualTo(registered.getEventId());
        assertThat(registeredJson.get("eventType").asText()).isEqualTo("TAX_REGISTERED");
        assertThat(registeredJson.get("timestamp")).isNotNull();
        assertThat(registeredJson.get("payload").get("taxInvoiceId").asLong()).isEqualTo(id);
        assertThat(registeredJson.get("payload").get("siteId").asLong()).isEqualTo(7L);

        taxService.confirmPayment(id, paymentRequest());
        OutboxEvent confirmed = outboxRepository.findAll().stream()
                .filter(event -> event.getTopic().equals("tax.payment.confirmed"))
                .findFirst().orElseThrow();
        assertThat(confirmed.getRecordKey()).isEqualTo(String.valueOf(id));
        var confirmedJson = objectMapper.readTree(confirmed.getPayloadJson());
        assertThat(confirmedJson.get("eventType").asText()).isEqualTo("TAX_PAYMENT_CONFIRMED");
        assertThat(confirmedJson.get("eventId").asText()).isEqualTo(confirmed.getEventId());
        assertThat(outboxRepository.count()).isEqualTo(2);
    }

    @Test
    void rollbackRemovesBusinessRecordAndOutboxTogether() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
            taxService.create(createRequest());
            throw new IllegalStateException("rollback after enqueue");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(taxRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void enqueueOutsideBusinessTransactionIsRejected() {
        assertThatThrownBy(() -> outboxWriter.enqueue("tax.registered", "7", "TAX_REGISTERED",
                java.util.Map.of("taxInvoiceId", 7L)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void concurrentPaymentConfirmationCreatesOnlyOneEvent() throws Exception {
        long id = taxService.create(createRequest()).getId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        taxService.confirmPayment(id, paymentRequest());
                        return true;
                    } catch (BusinessException alreadyConfirmed) {
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertThat(futures.get(0).get(10, TimeUnit.SECONDS)
                    ^ futures.get(1).get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(taxRepository.findById(id).orElseThrow().isPaymentConfirmed()).isTrue();
            assertThat(outboxRepository.count()).isEqualTo(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void retryAndLeaseRecoveryKeepOriginalPayloadAndRejectStaleTokens() {
        taxService.create(createRequest());
        LocalDateTime now = LocalDateTime.now().plusSeconds(1);
        OutboxClaim first = claimService.claimNext(now).orElseThrow();
        assertThat(claimService.claimNext(now.plusSeconds(1))).isEmpty();

        assertThat(claimService.markFailed(first, now, new IllegalStateException("broker down"))).isTrue();
        assertThat(outboxRepository.findById(first.eventId()).orElseThrow().getNextAttemptAt())
                .isAfterOrEqualTo(now.plusSeconds(1).minusNanos(1_000))
                .isBeforeOrEqualTo(now.plusSeconds(1).plusNanos(1_000));
        assertThat(claimService.claimNext(now)).isEmpty();
        OutboxClaim retry = claimService.claimNext(now.plusSeconds(2)).orElseThrow();
        assertThat(retry.eventId()).isEqualTo(first.eventId());
        assertThat(retry.payloadJson()).isEqualTo(first.payloadJson());
        assertThat(claimService.markSent(first, now.plusSeconds(3))).isFalse();

        OutboxClaim recovered = claimService.claimNext(now.plusSeconds(63)).orElseThrow();
        assertThat(recovered.eventId()).isEqualTo(first.eventId());
        assertThat(recovered.payloadJson()).isEqualTo(first.payloadJson());
        assertThat(claimService.markFailed(retry, now.plusSeconds(63), new IllegalStateException("stale")))
                .isFalse();
        assertThat(claimService.markSent(recovered, now.plusSeconds(64))).isTrue();
        assertThat(outboxRepository.findById(first.eventId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.SENT);
    }

    private TaxInvoiceCreateRequest createRequest() {
        TaxInvoiceCreateRequest request = new TaxInvoiceCreateRequest();
        ReflectionTestUtils.setField(request, "siteId", 7L);
        ReflectionTestUtils.setField(request, "type", TaxInvoiceType.SALES);
        ReflectionTestUtils.setField(request, "supplyAmount", new BigDecimal("100.00"));
        ReflectionTestUtils.setField(request, "taxAmount", new BigDecimal("10.00"));
        ReflectionTestUtils.setField(request, "counterparty", "거래처");
        ReflectionTestUtils.setField(request, "issueDate", LocalDate.of(2026, 9, 20));
        return request;
    }

    private PaymentConfirmRequest paymentRequest() {
        PaymentConfirmRequest request = new PaymentConfirmRequest();
        ReflectionTestUtils.setField(request, "paymentDate", LocalDate.of(2026, 9, 20));
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
