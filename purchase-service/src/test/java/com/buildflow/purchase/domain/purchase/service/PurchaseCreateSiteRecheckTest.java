package com.buildflow.purchase.domain.purchase.service;

import com.buildflow.purchase.domain.purchase.dto.PurchaseCreateRequest;
import com.buildflow.purchase.domain.purchase.repository.PurchaseRepository;
import com.buildflow.purchase.global.exception.BusinessException;
import com.buildflow.purchase.global.exception.ErrorCode;
import com.buildflow.purchase.global.kafka.KafkaProducerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 현장 보관 경합: 저장·outbox 기록 뒤 커밋 직전 재확인이 실패하면 예외를 전파해 트랜잭션을 롤백시킨다. */
class PurchaseCreateSiteRecheckTest {

    private final PurchaseRepository repository = mock(PurchaseRepository.class);
    private final KafkaProducerService producer = mock(KafkaProducerService.class);
    private final SiteReferenceValidator siteReferenceValidator = mock(SiteReferenceValidator.class);
    private final PurchaseService service = new PurchaseService(repository, producer, siteReferenceValidator);

    @Test
    void createRechecksSiteAfterSaveAndOutboxBeforeCommit() {
        when(repository.save(any())).then(returnsFirstArg());

        service.create(request());

        InOrder order = inOrder(siteReferenceValidator, repository, producer);
        order.verify(siteReferenceValidator).requireExistingSite(7L);
        order.verify(repository).save(any());
        order.verify(producer).sendPurchaseRegistered(any());
        order.verify(repository).flush();
        order.verify(siteReferenceValidator).recheckBeforeCommit(7L);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"SITE_ARCHIVED", "INVALID_SITE_REFERENCE", "SITE_REFERENCE_UNAVAILABLE"})
    void recheckFailureAfterSavePropagates(ErrorCode code) {
        when(repository.save(any())).then(returnsFirstArg());
        doThrow(new BusinessException(code)).when(siteReferenceValidator).recheckBeforeCommit(7L);

        BusinessException error = assertThrows(BusinessException.class, () -> service.create(request()));

        assertEquals(code, error.getErrorCode());
        verify(repository).save(any());
        verify(producer).sendPurchaseRegistered(any());
    }

    private PurchaseCreateRequest request() {
        PurchaseCreateRequest request = mock(PurchaseCreateRequest.class);
        when(request.getSiteId()).thenReturn(7L);
        when(request.getItemName()).thenReturn("자재");
        when(request.getQuantity()).thenReturn(2);
        when(request.getUnitPrice()).thenReturn(new BigDecimal("1000.00"));
        when(request.getPurchaseDate()).thenReturn(LocalDate.of(2026, 10, 28));
        return request;
    }
}
