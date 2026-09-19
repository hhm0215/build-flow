package com.buildflow.notification.domain.warranty.service;

import com.buildflow.notification.domain.warranty.dto.WarrantyUpdateRequest;
import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import com.buildflow.notification.global.exception.BusinessException;
import com.buildflow.notification.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefectWarrantyServiceTest {

    @Test
    void OCR_처리중에는_수동_수정을_거부한다() {
        DefectWarrantyRepository repository = mock(DefectWarrantyRepository.class);
        WarrantyOcrService ocrService = mock(WarrantyOcrService.class);
        DefectWarrantyService service = new DefectWarrantyService(repository, ocrService);
        DefectWarranty pending = DefectWarranty.createPending(1L, "warranty.pdf");
        when(repository.findById(7L)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.update(7L, mock(WarrantyUpdateRequest.class)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(
                        ((BusinessException) error).getErrorCode()).isEqualTo(ErrorCode.WARRANTY_OCR_PENDING));
        verifyNoInteractions(ocrService);
    }

    @Test
    void 역전된_기간은_저장하지_않는다() {
        DefectWarrantyRepository repository = mock(DefectWarrantyRepository.class);
        DefectWarrantyService service = new DefectWarrantyService(repository, mock(WarrantyOcrService.class));
        DefectWarranty warranty = DefectWarranty.builder()
                .siteId(1L)
                .insuranceCompany("기존 보험사")
                .startDate(LocalDate.of(2026, 1, 1))
                .endDate(LocalDate.of(2027, 1, 1))
                .build();
        WarrantyUpdateRequest request = mock(WarrantyUpdateRequest.class);
        when(repository.findById(7L)).thenReturn(Optional.of(warranty));
        when(request.getStartDate()).thenReturn(LocalDate.of(2027, 1, 1));
        when(request.getEndDate()).thenReturn(LocalDate.of(2026, 1, 1));

        assertThatThrownBy(() -> service.update(7L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(
                        ((BusinessException) error).getErrorCode()).isEqualTo(ErrorCode.WARRANTY_INVALID_PERIOD));
        verify(repository).findById(7L);
    }
}
