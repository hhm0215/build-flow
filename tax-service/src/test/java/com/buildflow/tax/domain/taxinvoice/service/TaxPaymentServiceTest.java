package com.buildflow.tax.domain.taxinvoice.service;

import com.buildflow.tax.domain.taxinvoice.dto.TaxInvoiceResponse;
import com.buildflow.tax.domain.taxinvoice.dto.TaxPaymentCancelRequest;
import com.buildflow.tax.domain.taxinvoice.dto.TaxPaymentCreateRequest;
import com.buildflow.tax.domain.taxinvoice.dto.TaxPaymentResponse;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoice;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoiceType;
import com.buildflow.tax.domain.taxinvoice.entity.TaxPayment;
import com.buildflow.tax.domain.taxinvoice.entity.TaxPaymentStatus;
import com.buildflow.tax.domain.taxinvoice.repository.TaxInvoiceRepository;
import com.buildflow.tax.domain.taxinvoice.repository.TaxPaymentRepository;
import com.buildflow.tax.global.exception.BusinessException;
import com.buildflow.tax.global.exception.ErrorCode;
import com.buildflow.tax.global.kafka.KafkaProducerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 메모리 원장으로 입금 저장소를 흉내 내 분할·취소·초과 규칙을 검증한다. */
class TaxPaymentServiceTest {

    private static final long INVOICE_ID = 11L;

    private final TaxInvoiceRepository invoiceRepository = mock(TaxInvoiceRepository.class);
    private final TaxPaymentRepository paymentRepository = mock(TaxPaymentRepository.class);
    private final KafkaProducerService kafka = mock(KafkaProducerService.class);
    private final SiteReferenceValidator siteValidator = mock(SiteReferenceValidator.class);
    private final TaxPaymentService service =
            new TaxPaymentService(invoiceRepository, paymentRepository, kafka, siteValidator);
    private final List<TaxPayment> ledger = new ArrayList<>();
    private TaxInvoice invoice;

    @BeforeEach
    void setUp() {
        invoice = invoice(TaxInvoiceType.SALES, "1000000", "100000");
        when(invoiceRepository.findByIdForUpdate(INVOICE_ID)).thenAnswer(ignored -> Optional.of(invoice));
        when(invoiceRepository.findById(INVOICE_ID)).thenAnswer(ignored -> Optional.of(invoice));
        when(paymentRepository.save(any(TaxPayment.class))).thenAnswer(call -> {
            TaxPayment payment = call.getArgument(0);
            ReflectionTestUtils.setField(payment, "id", (long) ledger.size() + 1);
            ledger.add(payment);
            return payment;
        });
        when(paymentRepository.sumActiveAmount(INVOICE_ID)).thenAnswer(ignored -> ledger.stream()
                .filter(payment -> !payment.isCanceled())
                .map(TaxPayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        when(paymentRepository.findLatestActivePaymentDate(INVOICE_ID)).thenAnswer(ignored -> ledger.stream()
                .filter(payment -> !payment.isCanceled())
                .map(TaxPayment::getPaymentDate)
                .max(Comparator.naturalOrder()));
        when(paymentRepository.existsByTaxInvoiceId(INVOICE_ID)).thenAnswer(ignored -> !ledger.isEmpty());
        when(paymentRepository.findByIdAndTaxInvoiceId(anyLong(), any())).thenAnswer(call -> ledger.stream()
                .filter(payment -> payment.getId().equals(call.getArgument(0)))
                .findFirst());
    }

    @Test
    void 분할_입금_두_번이면_완료되고_완료_이벤트는_한_번만_발행된다() {
        service.record(INVOICE_ID, request("400000", LocalDate.of(2026, 10, 5)));

        assertThat(invoice.isPaymentConfirmed()).isFalse();
        assertThat(invoice.getPaymentDate()).isNull();
        TaxInvoiceResponse partial = TaxInvoiceResponse.from(invoice, service.paidAmount(invoice));
        assertThat(partial.getPaymentStatus()).isEqualTo(TaxPaymentStatus.PARTIAL);
        assertThat(partial.getRemainingAmount()).isEqualByComparingTo("700000");
        verify(kafka, never()).sendTaxPaymentConfirmed(any());

        service.record(INVOICE_ID, request("700000.00", LocalDate.of(2026, 10, 20)));

        assertThat(invoice.isPaymentConfirmed()).isTrue();
        assertThat(invoice.getPaymentDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(TaxInvoiceResponse.from(invoice, service.paidAmount(invoice)).getPaymentStatus())
                .isEqualTo(TaxPaymentStatus.PAID);
        verify(kafka, times(1)).sendTaxPaymentConfirmed(any());
    }

    @Test
    void 잔액을_넘는_입금은_400이고_원장이_바뀌지_않는다() {
        service.record(INVOICE_ID, request("1000000", LocalDate.of(2026, 10, 5)));

        assertThatThrownBy(() -> service.record(INVOICE_ID, request("100000.01", LocalDate.of(2026, 10, 6))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TAX_PAYMENT_EXCEEDS_BALANCE);
                    assertThat(e.getErrorCode().getStatus().value()).isEqualTo(400);
                });
        assertThat(ledger).hasSize(1);
    }

    @Test
    void 완료된_계산서에_추가_입금은_초과_400이다() {
        service.record(INVOICE_ID, request("1100000", LocalDate.of(2026, 10, 5)));

        assertThatThrownBy(() -> service.record(INVOICE_ID, request("0.01", LocalDate.of(2026, 10, 6))))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TAX_PAYMENT_EXCEEDS_BALANCE));
    }

    @Test
    void 취소하면_합계에서_빠지고_완료가_풀리며_재취소는_409다() {
        service.record(INVOICE_ID, request("600000", LocalDate.of(2026, 10, 5)));
        TaxPaymentResponse second = service.record(INVOICE_ID, request("500000", LocalDate.of(2026, 10, 9)));
        assertThat(invoice.isPaymentConfirmed()).isTrue();

        TaxPaymentResponse canceled = service.cancel(INVOICE_ID, second.getId(), cancelRequest("금액 오입력"));

        assertThat(canceled.isCanceled()).isTrue();
        assertThat(canceled.getCanceledAt()).isNotNull();
        assertThat(canceled.getCancelReason()).isEqualTo("금액 오입력");
        assertThat(ledger).hasSize(2); // 삭제가 아니라 취소 기록
        assertThat(invoice.isPaymentConfirmed()).isFalse();
        assertThat(invoice.getPaymentDate()).isNull();
        assertThat(service.paidAmount(invoice)).isEqualByComparingTo("600000");

        assertThatThrownBy(() -> service.cancel(INVOICE_ID, second.getId(), null))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TAX_PAYMENT_ALREADY_CANCELED);
                    assertThat(e.getErrorCode().getStatus().value()).isEqualTo(409);
                });

        // 잔액 복구 후 다시 입금하면 재완료 — 완료 이벤트는 실제 완료 전이마다 발행
        service.record(INVOICE_ID, request("500000", LocalDate.of(2026, 10, 12)));
        assertThat(invoice.isPaymentConfirmed()).isTrue();
        verify(kafka, times(2)).sendTaxPaymentConfirmed(any());
    }

    @Test
    void 다른_계산서의_입금이나_없는_입금은_404다() {
        assertThatThrownBy(() -> service.cancel(INVOICE_ID, 999L, null))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TAX_PAYMENT_NOT_FOUND));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "-0.01", "1.001", "10000000000000.00", "9999999999999.991"})
    void 금액_경계를_벗어나면_400이다(String amount) {
        assertThatThrownBy(() -> service.record(INVOICE_ID, request(amount, LocalDate.of(2026, 10, 5))))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_TAX_PAYMENT_AMOUNT));
        assertThat(ledger).isEmpty();
    }

    @Test
    void 최대_금액_계산서는_최대값_한_건으로_완료된다() {
        invoice = invoice(TaxInvoiceType.SALES, "9999999999999.98", "0.01");

        service.record(INVOICE_ID, request("9999999999999.99", LocalDate.of(2026, 10, 5)));

        assertThat(invoice.isPaymentConfirmed()).isTrue();
        assertThat(ledger.get(0).getAmount()).isEqualByComparingTo("9999999999999.99");
        assertThat(ledger.get(0).getAmount().scale()).isEqualTo(2);
    }

    @Test
    void 소수_둘째자리_입금은_반올림_없이_저장된다() {
        service.record(INVOICE_ID, request("0.01", LocalDate.of(2026, 10, 5)));

        assertThat(ledger.get(0).getAmount()).isEqualByComparingTo("0.01");
        assertThat(service.paidAmount(invoice)).isEqualByComparingTo("0.01");
    }

    @Test
    void 입금일이_없으면_400이다() {
        assertThatThrownBy(() -> service.record(INVOICE_ID, request("1", null)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_TAX_PAYMENT_DATE));
    }

    @Test
    void 메모가_500자를_넘으면_400이다() {
        TaxPaymentCreateRequest request = request("1", LocalDate.of(2026, 10, 5));
        ReflectionTestUtils.setField(request, "memo", "가".repeat(501));

        assertThatThrownBy(() -> service.record(INVOICE_ID, request))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_TAX_PAYMENT_MEMO));
    }

    @Test
    void 매입_계산서에는_입금을_기록할_수_없다() {
        invoice = invoice(TaxInvoiceType.PURCHASE, "100", "10");

        assertThatThrownBy(() -> service.record(INVOICE_ID, request("10", LocalDate.of(2026, 10, 5))))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PURCHASE_TAX_INVOICE_PAYMENT_NOT_ALLOWED));
        assertThat(service.paidAmount(invoice)).isNull();
    }

    @Test
    void 보관_현장은_입금_기록은_허용하고_취소는_409로_막는다() {
        doThrow(new BusinessException(ErrorCode.SITE_ARCHIVED_DOCUMENT_LOCKED))
                .when(siteValidator).requireSiteNotArchived(7L);

        TaxPaymentResponse recorded = service.record(INVOICE_ID, request("100", LocalDate.of(2026, 10, 5)));
        assertThat(ledger).hasSize(1);

        assertThatThrownBy(() -> service.cancel(INVOICE_ID, recorded.getId(), null))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SITE_ARCHIVED_DOCUMENT_LOCKED));
        assertThat(ledger.get(0).isCanceled()).isFalse();
    }

    @Test
    void 하위호환_입금확인은_잔액_전부를_한_건으로_기록한다() {
        service.record(INVOICE_ID, request("300000", LocalDate.of(2026, 10, 5)));

        service.payRemaining(INVOICE_ID, LocalDate.of(2026, 10, 7));

        assertThat(ledger).hasSize(2);
        assertThat(ledger.get(1).getAmount()).isEqualByComparingTo("800000");
        assertThat(invoice.isPaymentConfirmed()).isTrue();
        assertThat(invoice.getPaymentDate()).isEqualTo(LocalDate.of(2026, 10, 7));
        assertThatThrownBy(() -> service.payRemaining(INVOICE_ID, null))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ALREADY_PAYMENT_CONFIRMED));
    }

    @Test
    void 공급가와_세액이_0인_계산서는_입금확인할_잔액이_없다() {
        invoice = invoice(TaxInvoiceType.SALES, "0", "0");

        assertThatThrownBy(() -> service.payRemaining(INVOICE_ID, null))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NO_REMAINING_BALANCE));
    }

    @Test
    void 기존_입금확인_자료는_입금행이_없어도_총액_입금으로_보고_변경시_한_건으로_이관한다() {
        ReflectionTestUtils.setField(invoice, "paymentConfirmed", true);
        ReflectionTestUtils.setField(invoice, "paymentDate", LocalDate.of(2026, 9, 1));

        assertThat(service.paidAmount(invoice)).isEqualByComparingTo("1100000");

        when(invoiceRepository.findConfirmedIdsWithUnrecordedBalance()).thenReturn(List.of(INVOICE_ID));
        assertThat(service.backfillLegacyConfirmed()).isEqualTo(1);
        assertThat(service.backfillLegacyConfirmed()).isZero(); // 멱등

        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).getAmount()).isEqualByComparingTo("1100000");
        assertThat(ledger.get(0).getPaymentDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(service.paidAmount(invoice)).isEqualByComparingTo("1100000");

        service.cancel(INVOICE_ID, ledger.get(0).getId(), cancelRequest("오입금"));
        assertThat(invoice.isPaymentConfirmed()).isFalse();
        assertThat(service.paidAmount(invoice)).isEqualByComparingTo("0");
    }

    @Test
    void 되돌린_동안_부분입금_뒤_입금확인된_계산서는_잔액을_한_건으로_채운다() {
        // 새 코드로 30만 입금 → 이전 코드로 되돌린 동안 입금 확인 → 다시 올림 (2026-10-23 운영형 리허설 WARN)
        service.record(INVOICE_ID, request("300000", LocalDate.of(2026, 10, 2)));
        ReflectionTestUtils.setField(invoice, "paymentConfirmed", true);
        ReflectionTestUtils.setField(invoice, "paymentDate", LocalDate.of(2026, 10, 5));

        assertThat(service.paidAmount(invoice)).isEqualByComparingTo("1100000");

        when(invoiceRepository.findConfirmedIdsWithUnrecordedBalance()).thenReturn(List.of(INVOICE_ID));
        assertThat(service.backfillLegacyConfirmed()).isEqualTo(1);
        assertThat(service.backfillLegacyConfirmed()).isZero();

        assertThat(ledger).hasSize(2);
        assertThat(ledger.get(1).getAmount()).isEqualByComparingTo("800000");
        assertThat(ledger.get(1).getMemo()).isEqualTo(TaxPaymentService.LEGACY_MEMO);
        assertThat(service.paidAmount(invoice)).isEqualByComparingTo("1100000");
    }

    private TaxInvoice invoice(TaxInvoiceType type, String supply, String tax) {
        TaxInvoice created = TaxInvoice.builder()
                .siteId(7L)
                .type(type)
                .supplyAmount(new BigDecimal(supply))
                .taxAmount(new BigDecimal(tax))
                .issueDate(LocalDate.of(2026, 10, 1))
                .build();
        ReflectionTestUtils.setField(created, "id", INVOICE_ID);
        return created;
    }

    private static TaxPaymentCreateRequest request(String amount, LocalDate date) {
        TaxPaymentCreateRequest request = new TaxPaymentCreateRequest();
        ReflectionTestUtils.setField(request, "amount", new BigDecimal(amount));
        ReflectionTestUtils.setField(request, "paymentDate", date);
        return request;
    }

    private static TaxPaymentCancelRequest cancelRequest(String reason) {
        TaxPaymentCancelRequest request = new TaxPaymentCancelRequest();
        ReflectionTestUtils.setField(request, "reason", reason);
        return request;
    }

    @Test
    void 기동_이관은_커밋된_입금_행을_보도록_READ_COMMITTED로_실행한다() throws Exception {
        // REPEATABLE READ면 잠금 대기 중 다른 요청이 넣은 이관 행을 못 보고 중복 생성한다(PR #193 리뷰).
        org.springframework.transaction.annotation.Transactional tx = TaxPaymentService.class
                .getMethod("backfillLegacyConfirmed")
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(tx.isolation()).isEqualTo(org.springframework.transaction.annotation.Isolation.READ_COMMITTED);
    }
}
