package com.buildflow.purchase.domain.purchase.service;

import com.buildflow.purchase.domain.purchase.dto.PurchaseUpdateRequest;
import com.buildflow.purchase.domain.purchase.entity.Purchase;
import com.buildflow.purchase.domain.purchase.repository.PurchaseRepository;
import com.buildflow.purchase.global.exception.BusinessException;
import com.buildflow.purchase.global.exception.ErrorCode;
import com.buildflow.purchase.global.kafka.KafkaProducerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.buildflow.purchase.domain.purchase.event.PurchaseDeletedPayload;
import com.buildflow.purchase.domain.purchase.event.PurchaseUpdatedPayload;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 현장 보관 경합: 기존 매입 수정·삭제는 변경·outbox flush 뒤 커밋 직전에 보관 여부를 다시 확인한다. */
class PurchaseMutationSiteRecheckTest {

    private final PurchaseRepository repository = mock(PurchaseRepository.class);
    private final KafkaProducerService producer = mock(KafkaProducerService.class);
    private final SiteReferenceValidator siteReferenceValidator = mock(SiteReferenceValidator.class);
    private final PurchaseService service = new PurchaseService(repository, producer, siteReferenceValidator);

    @Test
    void updateRechecksAfterOutboxAndFlush() {
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(purchase()));

        service.update(1L, updateRequest());

        InOrder order = inOrder(siteReferenceValidator, repository, producer);
        order.verify(siteReferenceValidator).requireSiteNotArchived(7L);
        order.verify(producer).sendPurchaseUpdated(any());
        order.verify(repository).flush();
        order.verify(siteReferenceValidator).recheckNotArchivedBeforeCommit(7L);
    }

    @Test
    void deleteRechecksAfterOutboxDeleteAndFlush() {
        Purchase purchase = purchase();
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(purchase));

        service.delete(1L);

        InOrder order = inOrder(siteReferenceValidator, repository, producer);
        order.verify(siteReferenceValidator).requireSiteNotArchived(7L);
        order.verify(producer).sendPurchaseDeleted(any());
        order.verify(repository).delete(purchase);
        order.verify(repository).flush();
        order.verify(siteReferenceValidator).recheckNotArchivedBeforeCommit(7L);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"SITE_ARCHIVED_DOCUMENT_LOCKED", "SITE_REFERENCE_UNAVAILABLE"})
    void recheckFailurePropagates(ErrorCode code) {
        when(repository.findByIdForUpdate(1L)).thenAnswer(invocation -> Optional.of(purchase()));
        doThrow(new BusinessException(code)).when(siteReferenceValidator).recheckNotArchivedBeforeCommit(7L);

        assertEquals(code, assertThrows(BusinessException.class,
                () -> service.update(1L, updateRequest())).getErrorCode());
        assertEquals(code, assertThrows(BusinessException.class,
                () -> service.delete(1L)).getErrorCode());
        verify(siteReferenceValidator, times(2)).requireSiteNotArchived(7L);
    }

    @Test
    void eventPayloadsCarryDisplayNamesForNotifications() {
        when(repository.findByIdForUpdate(1L)).thenAnswer(invocation -> Optional.of(purchase()));

        service.update(1L, updateRequest());
        service.delete(1L);

        ArgumentCaptor<PurchaseUpdatedPayload> updated = ArgumentCaptor.forClass(PurchaseUpdatedPayload.class);
        verify(producer).sendPurchaseUpdated(updated.capture());
        assertEquals("자재", updated.getValue().getItemName());
        assertNull(updated.getValue().getSupplier());

        ArgumentCaptor<PurchaseDeletedPayload> deleted = ArgumentCaptor.forClass(PurchaseDeletedPayload.class);
        verify(producer).sendPurchaseDeleted(deleted.capture());
        assertEquals("자재", deleted.getValue().getItemName());
        assertEquals("대한건자재", deleted.getValue().getSupplier());
    }

    private Purchase purchase() {
        return Purchase.builder()
                .siteId(7L)
                .itemName("자재")
                .quantity(2)
                .unitPrice(new BigDecimal("1000.00"))
                .supplier("대한건자재")
                .purchaseDate(LocalDate.of(2026, 10, 28))
                .build();
    }

    private PurchaseUpdateRequest updateRequest() {
        PurchaseUpdateRequest request = mock(PurchaseUpdateRequest.class);
        when(request.getItemName()).thenReturn("자재");
        when(request.getQuantity()).thenReturn(3);
        when(request.getUnitPrice()).thenReturn(new BigDecimal("1000.00"));
        when(request.getPurchaseDate()).thenReturn(LocalDate.of(2026, 10, 28));
        return request;
    }
}
