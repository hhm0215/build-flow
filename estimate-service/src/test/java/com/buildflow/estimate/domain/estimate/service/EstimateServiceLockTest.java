package com.buildflow.estimate.domain.estimate.service;

import com.buildflow.estimate.domain.estimate.dto.EstimateItemRequest;
import com.buildflow.estimate.domain.estimate.dto.EstimateUpdateRequest;
import com.buildflow.estimate.domain.estimate.entity.Estimate;
import com.buildflow.estimate.domain.estimate.repository.EstimateRepository;
import com.buildflow.estimate.global.kafka.KafkaProducerService;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Lock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EstimateServiceLockTest {

    @Mock EstimateRepository estimateRepository;
    @Mock KafkaProducerService kafkaProducerService;
    @InjectMocks EstimateService estimateService;

    @Test
    void mutationLookupUsesPessimisticWriteLock() throws NoSuchMethodException {
        Lock lock = EstimateRepository.class
                .getMethod("findByIdForUpdate", Long.class)
                .getAnnotation(Lock.class);
        assertEquals(LockModeType.PESSIMISTIC_WRITE, lock.value());
    }

    @Test
    void updateReadsLockedEstimate() {
        Estimate estimate = draft();
        when(estimateRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(estimate));
        EstimateUpdateRequest request = mock(EstimateUpdateRequest.class);
        EstimateItemRequest item = mock(EstimateItemRequest.class);
        when(request.getTitle()).thenReturn("수정 견적");
        when(request.getEstimateDate()).thenReturn(LocalDate.of(2026, 9, 20));
        when(request.getItems()).thenReturn(List.of(item));
        when(item.getQuantity()).thenReturn(BigDecimal.ONE);
        when(item.getUnitPrice()).thenReturn(BigDecimal.valueOf(1000));
        when(item.getItemName()).thenReturn("자재");
        when(item.getUnit()).thenReturn("EA");

        estimateService.update(7L, request);

        verify(estimateRepository).findByIdForUpdate(7L);
        verify(estimateRepository, never()).findById(7L);
        assertEquals("수정 견적", estimate.getTitle());
    }

    @Test
    void confirmReadsLockedEstimateBeforePublishing() {
        Estimate estimate = draft();
        when(estimateRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(estimate));

        estimateService.confirm(7L);

        verify(estimateRepository).findByIdForUpdate(7L);
        verify(estimateRepository, never()).findById(7L);
        verify(kafkaProducerService).sendEstimateParsed(any());
    }

    @Test
    void deleteReadsLockedEstimate() {
        Estimate estimate = draft();
        when(estimateRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(estimate));

        estimateService.delete(7L);

        verify(estimateRepository).findByIdForUpdate(7L);
        verify(estimateRepository).delete(estimate);
    }

    private Estimate draft() {
        return Estimate.builder()
                .siteId(3L)
                .title("초안")
                .estimateDate(LocalDate.of(2026, 9, 19))
                .totalAmount(BigDecimal.valueOf(1000))
                .build();
    }
}
