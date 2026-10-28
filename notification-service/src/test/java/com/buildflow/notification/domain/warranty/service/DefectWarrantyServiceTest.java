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

    @Test
    void 수동_보정은_변경과_이력_flush_뒤_보관_여부를_다시_확인한다() {
        DefectWarrantyRepository repository = mock(DefectWarrantyRepository.class);
        SiteReferenceValidator siteReferenceValidator = mock(SiteReferenceValidator.class);
        WarrantyAuditRecorder auditRecorder = mock(WarrantyAuditRecorder.class);
        DefectWarrantyService service = new DefectWarrantyService(repository, mock(WarrantyOcrService.class), mock(WarrantyAuditEventRepository.class), siteReferenceValidator, auditRecorder, mock(WarrantyFileRetentionService.class), new TransactionTemplate(mock(PlatformTransactionManager.class)));
        when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(manualWarranty()));

        service.update(7L, validUpdate());

        var order = org.mockito.Mockito.inOrder(siteReferenceValidator, repository, auditRecorder);
        order.verify(siteReferenceValidator).requireSiteNotArchived(1L);
        order.verify(auditRecorder).record(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        order.verify(repository).flush();
        order.verify(siteReferenceValidator).recheckNotArchivedBeforeCommit(1L);
    }

    @Test
    void 삭제는_이력_보존기록_삭제_flush_뒤_보관_여부를_다시_확인한다() {
        DefectWarrantyRepository repository = mock(DefectWarrantyRepository.class);
        SiteReferenceValidator siteReferenceValidator = mock(SiteReferenceValidator.class);
        WarrantyAuditRecorder auditRecorder = mock(WarrantyAuditRecorder.class);
        WarrantyFileRetentionService retention = mock(WarrantyFileRetentionService.class);
        DefectWarrantyService service = new DefectWarrantyService(repository, mock(WarrantyOcrService.class), mock(WarrantyAuditEventRepository.class), siteReferenceValidator, auditRecorder, retention, new TransactionTemplate(mock(PlatformTransactionManager.class)));
        DefectWarranty warranty = manualWarranty();
        when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(warranty));

        service.delete(7L);

        var order = org.mockito.Mockito.inOrder(siteReferenceValidator, repository, auditRecorder, retention);
        order.verify(siteReferenceValidator).requireSiteNotArchived(1L);
        order.verify(auditRecorder).record(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        order.verify(retention).retain(warranty);
        order.verify(repository).delete(warranty);
        order.verify(repository).flush();
        order.verify(siteReferenceValidator).recheckNotArchivedBeforeCommit(1L);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ErrorCode.class,
            names = {"SITE_ARCHIVED_DOCUMENT_LOCKED", "SITE_REFERENCE_UNAVAILABLE"})
    void 수정_삭제의_커밋_직전_재확인_실패를_전파한다(ErrorCode code) {
        DefectWarrantyRepository repository = mock(DefectWarrantyRepository.class);
        SiteReferenceValidator siteReferenceValidator = mock(SiteReferenceValidator.class);
        DefectWarrantyService service = new DefectWarrantyService(repository, mock(WarrantyOcrService.class), mock(WarrantyAuditEventRepository.class), siteReferenceValidator, mock(WarrantyAuditRecorder.class), mock(WarrantyFileRetentionService.class), new TransactionTemplate(mock(PlatformTransactionManager.class)));
        when(repository.findByIdForUpdate(7L)).thenAnswer(invocation -> Optional.of(manualWarranty()));
        doThrow(new BusinessException(code)).when(siteReferenceValidator).recheckNotArchivedBeforeCommit(1L);

        assertThatThrownBy(() -> service.update(7L, validUpdate()))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        org.assertj.core.api.Assertions.assertThat(error.getErrorCode()).isEqualTo(code));
        assertThatThrownBy(() -> service.delete(7L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        org.assertj.core.api.Assertions.assertThat(error.getErrorCode()).isEqualTo(code));
    }

    private static DefectWarranty manualWarranty() {
        return DefectWarranty.builder()
                .siteId(1L)
                .insuranceCompany("기존 보험사")
                .startDate(LocalDate.of(2026, 1, 1))
                .endDate(LocalDate.of(2027, 1, 1))
                .build();
    }

    private static WarrantyUpdateRequest validUpdate() {
        WarrantyUpdateRequest request = mock(WarrantyUpdateRequest.class);
        when(request.getInsuranceCompany()).thenReturn("바뀐 보험사");
        when(request.getStartDate()).thenReturn(LocalDate.of(2026, 2, 1));
        when(request.getEndDate()).thenReturn(LocalDate.of(2027, 2, 1));
        return request;
    }
}
