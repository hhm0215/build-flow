package com.buildflow.notification.domain.warranty.service;

import com.buildflow.notification.domain.warranty.repository.WarrantyAuditEventRepository;

import com.buildflow.notification.domain.warranty.dto.WarrantyCreateRequest;
import com.buildflow.notification.domain.warranty.dto.WarrantyUpdateRequest;
import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import com.buildflow.notification.global.exception.BusinessException;
import com.buildflow.notification.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefectWarrantyServiceTest {

    @Test
    void OCR_처리중에는_수동_수정을_거부한다() {
        DefectWarrantyRepository repository = mock(DefectWarrantyRepository.class);
        WarrantyOcrService ocrService = mock(WarrantyOcrService.class);
        DefectWarrantyService service = new DefectWarrantyService(repository, ocrService, mock(WarrantyAuditEventRepository.class), mock(SiteReferenceValidator.class), mock(WarrantyAuditRecorder.class), mock(WarrantyFileRetentionService.class), new TransactionTemplate(mock(PlatformTransactionManager.class)));
        DefectWarranty pending = DefectWarranty.createPending(1L, "warranty.pdf");
        when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.update(7L, mock(WarrantyUpdateRequest.class)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(
                        ((BusinessException) error).getErrorCode()).isEqualTo(ErrorCode.WARRANTY_OCR_PENDING));
        verifyNoInteractions(ocrService);
    }

    @Test
    void 역전된_기간은_저장하지_않는다() {
        DefectWarrantyRepository repository = mock(DefectWarrantyRepository.class);
        DefectWarrantyService service = new DefectWarrantyService(repository, mock(WarrantyOcrService.class), mock(WarrantyAuditEventRepository.class), mock(SiteReferenceValidator.class), mock(WarrantyAuditRecorder.class), mock(WarrantyFileRetentionService.class), new TransactionTemplate(mock(PlatformTransactionManager.class)));
        DefectWarranty warranty = DefectWarranty.builder()
                .siteId(1L)
                .insuranceCompany("기존 보험사")
                .startDate(LocalDate.of(2026, 1, 1))
                .endDate(LocalDate.of(2027, 1, 1))
                .build();
        WarrantyUpdateRequest request = mock(WarrantyUpdateRequest.class);
        when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(warranty));
        when(request.getStartDate()).thenReturn(LocalDate.of(2027, 1, 1));
        when(request.getEndDate()).thenReturn(LocalDate.of(2026, 1, 1));

        assertThatThrownBy(() -> service.update(7L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(
                        ((BusinessException) error).getErrorCode()).isEqualTo(ErrorCode.WARRANTY_INVALID_PERIOD));
        verify(repository).findByIdForUpdate(7L);
    }

    @Test
    void 없는_현장의_직접_등록은_저장하지_않는다() {
        DefectWarrantyRepository repository = mock(DefectWarrantyRepository.class);
        SiteReferenceValidator siteReferenceValidator = mock(SiteReferenceValidator.class);
        DefectWarrantyService service = new DefectWarrantyService(repository, mock(WarrantyOcrService.class), mock(WarrantyAuditEventRepository.class), siteReferenceValidator, mock(WarrantyAuditRecorder.class), mock(WarrantyFileRetentionService.class), new TransactionTemplate(mock(PlatformTransactionManager.class)));
        WarrantyCreateRequest request = mock(WarrantyCreateRequest.class);
        when(request.getSiteId()).thenReturn(99L);
        when(request.getStartDate()).thenReturn(LocalDate.of(2026, 1, 1));
        when(request.getEndDate()).thenReturn(LocalDate.of(2027, 1, 1));
        doThrow(new BusinessException(ErrorCode.INVALID_SITE_REFERENCE))
                .when(siteReferenceValidator).requireExistingSite(99L);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(
                        ((BusinessException) error).getErrorCode()).isEqualTo(ErrorCode.INVALID_SITE_REFERENCE));
        verifyNoInteractions(repository);
    }

    @Test
    void 직접_등록은_저장_후_현장_재확인_실패를_전파해_롤백시킨다() {
        DefectWarrantyRepository repository = mock(DefectWarrantyRepository.class);
        SiteReferenceValidator siteReferenceValidator = mock(SiteReferenceValidator.class);
        WarrantyAuditRecorder auditRecorder = mock(WarrantyAuditRecorder.class);
        DefectWarrantyService service = new DefectWarrantyService(repository, mock(WarrantyOcrService.class), mock(WarrantyAuditEventRepository.class), siteReferenceValidator, auditRecorder, mock(WarrantyFileRetentionService.class), new TransactionTemplate(mock(PlatformTransactionManager.class)));
        WarrantyCreateRequest request = mock(WarrantyCreateRequest.class);
        when(request.getSiteId()).thenReturn(1L);
        when(request.getStartDate()).thenReturn(LocalDate.of(2026, 1, 1));
        when(request.getEndDate()).thenReturn(LocalDate.of(2027, 1, 1));
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new BusinessException(ErrorCode.SITE_ARCHIVED))
                .when(siteReferenceValidator).recheckBeforeCommit(1L);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(
                        ((BusinessException) error).getErrorCode()).isEqualTo(ErrorCode.SITE_ARCHIVED));
        var order = org.mockito.Mockito.inOrder(siteReferenceValidator, repository, auditRecorder);
        order.verify(siteReferenceValidator).requireExistingSite(1L);
        order.verify(repository).save(org.mockito.ArgumentMatchers.any());
        order.verify(auditRecorder).record(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        order.verify(repository).flush();
        order.verify(siteReferenceValidator).recheckBeforeCommit(1L);
    }
}
