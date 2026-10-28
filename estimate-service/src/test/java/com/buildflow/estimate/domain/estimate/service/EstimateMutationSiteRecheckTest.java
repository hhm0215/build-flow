package com.buildflow.estimate.domain.estimate.service;

import com.buildflow.estimate.domain.estimate.dto.EstimateItemRequest;
import com.buildflow.estimate.domain.estimate.dto.EstimateUpdateRequest;
import com.buildflow.estimate.domain.estimate.entity.Estimate;
import com.buildflow.estimate.domain.estimate.entity.EstimateStatus;
import com.buildflow.estimate.domain.estimate.repository.EstimateRepository;
import com.buildflow.estimate.global.exception.BusinessException;
import com.buildflow.estimate.global.exception.ErrorCode;
import com.buildflow.estimate.global.kafka.KafkaProducerService;
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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 현장 보관 경합: 기존 견적 수정·삭제·확정은 변경·outbox flush 뒤 커밋 직전에 보관 여부를 다시 확인한다. */
@ExtendWith(MockitoExtension.class)
class EstimateMutationSiteRecheckTest {

    private static final long SITE = 3L;

    @Mock EstimateRepository estimateRepository;
    @Mock KafkaProducerService kafkaProducerService;
    @Mock SiteReferenceValidator siteReferenceValidator;
    @InjectMocks EstimateService estimateService;

    @Test
    void updateRechecksAfterFlush() {
        when(estimateRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(draft()));

        estimateService.update(7L, updateRequest());

        InOrder order = inOrder(siteReferenceValidator, estimateRepository);
        order.verify(siteReferenceValidator).requireSiteNotArchived(SITE);
        order.verify(estimateRepository).flush();
        order.verify(siteReferenceValidator).recheckNotArchivedBeforeCommit(SITE);
    }

    @Test
    void deleteRechecksAfterDeleteAndFlush() {
        Estimate estimate = draft();
        when(estimateRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(estimate));

        estimateService.delete(7L);

        InOrder order = inOrder(siteReferenceValidator, estimateRepository);
        order.verify(siteReferenceValidator).requireSiteNotArchived(SITE);
        order.verify(estimateRepository).delete(estimate);
        order.verify(estimateRepository).flush();
        order.verify(siteReferenceValidator).recheckNotArchivedBeforeCommit(SITE);
    }

    @Test
    void confirmRechecksAfterOutboxAndFlush() {
        Estimate estimate = draft();
        when(estimateRepository.findChainRootId(7L)).thenReturn(Optional.of(7L));
        when(estimateRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(estimate));
        when(estimateRepository.findRevisionsByStatusForUpdate(7L, EstimateStatus.CONFIRMED)).thenReturn(List.of());

        estimateService.confirm(7L);

        InOrder order = inOrder(siteReferenceValidator, estimateRepository, kafkaProducerService);
        order.verify(siteReferenceValidator).requireSiteNotArchived(SITE);
        order.verify(kafkaProducerService).sendEstimateParsed(any());
        order.verify(estimateRepository).flush();
        order.verify(siteReferenceValidator).recheckNotArchivedBeforeCommit(SITE);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"SITE_ARCHIVED_DOCUMENT_LOCKED", "SITE_REFERENCE_UNAVAILABLE"})
    void recheckFailurePropagatesFromEveryMutation(ErrorCode code) {
        when(estimateRepository.findByIdForUpdate(7L)).thenAnswer(invocation -> Optional.of(draft()));
        when(estimateRepository.findChainRootId(7L)).thenReturn(Optional.of(7L));
        doThrow(new BusinessException(code)).when(siteReferenceValidator).recheckNotArchivedBeforeCommit(SITE);

        assertEquals(code, assertThrows(BusinessException.class,
                () -> estimateService.update(7L, updateRequest())).getErrorCode());
        assertEquals(code, assertThrows(BusinessException.class,
                () -> estimateService.delete(7L)).getErrorCode());
        assertEquals(code, assertThrows(BusinessException.class,
                () -> estimateService.confirm(7L)).getErrorCode());
        verify(kafkaProducerService).sendEstimateParsed(any());
    }

    private EstimateUpdateRequest updateRequest() {
        EstimateUpdateRequest request = mock(EstimateUpdateRequest.class);
        EstimateItemRequest item = mock(EstimateItemRequest.class);
        when(request.getTitle()).thenReturn("수정 견적");
        when(request.getEstimateDate()).thenReturn(LocalDate.of(2026, 10, 28));
        when(request.getItems()).thenReturn(List.of(item));
        when(item.getQuantity()).thenReturn(BigDecimal.ONE);
        when(item.getUnitPrice()).thenReturn(BigDecimal.valueOf(1000));
        when(item.getItemName()).thenReturn("자재");
        when(item.getUnit()).thenReturn("EA");
        return request;
    }

    private Estimate draft() {
        return Estimate.builder()
                .siteId(SITE)
                .title("초안")
                .estimateDate(LocalDate.of(2026, 10, 28))
                .totalAmount(BigDecimal.valueOf(1000))
                .build();
    }
}
