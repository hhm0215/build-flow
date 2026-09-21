package com.buildflow.purchase.domain.purchase.service;

import com.buildflow.purchase.domain.purchase.dto.PurchaseUpdateRequest;
import com.buildflow.purchase.domain.purchase.entity.Purchase;
import com.buildflow.purchase.domain.purchase.repository.PurchaseRepository;
import com.buildflow.purchase.global.kafka.KafkaProducerService;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class PurchaseServiceLockTest {

    private final PurchaseRepository repository = mock(PurchaseRepository.class);
    private final KafkaProducerService producer = mock(KafkaProducerService.class);
    private final PurchaseService service = new PurchaseService(repository, producer);

    @Test
    void mutationLookupUsesPessimisticWriteLock() throws NoSuchMethodException {
        Lock lock = PurchaseRepository.class.getMethod("findByIdForUpdate", Long.class).getAnnotation(Lock.class);
        assertEquals(LockModeType.PESSIMISTIC_WRITE, lock.value());
    }

    @Test
    void updateReadsLockedPurchaseBeforeDelta() {
        Purchase purchase = purchase();
        PurchaseUpdateRequest request = mock(PurchaseUpdateRequest.class);
        when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(purchase));
        when(request.getItemName()).thenReturn("수정 자재");
        when(request.getQuantity()).thenReturn(3);
        when(request.getUnitPrice()).thenReturn(new BigDecimal("2000.00"));

        service.update(7L, request);

        verify(repository).findByIdForUpdate(7L);
        verify(repository, never()).findById(7L);
        assertEquals(0, purchase.getTotalAmount().compareTo(new BigDecimal("6000.00")));
        verify(producer).sendPurchaseUpdated(any());
    }

    @Test
    void deleteReadsLockedPurchaseBeforeEvent() {
        Purchase purchase = purchase();
        when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(purchase));

        service.delete(7L);

        verify(repository).findByIdForUpdate(7L);
        verify(repository, never()).findById(7L);
        verify(producer).sendPurchaseDeleted(any());
        verify(repository).delete(purchase);
    }

    private Purchase purchase() {
        return Purchase.builder()
                .siteId(3L)
                .itemName("자재")
                .quantity(2)
                .unitPrice(new BigDecimal("1000.00"))
                .build();
    }
}
