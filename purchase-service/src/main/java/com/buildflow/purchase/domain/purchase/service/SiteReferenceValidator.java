package com.buildflow.purchase.domain.purchase.service;

import com.buildflow.purchase.global.exception.BusinessException;
import com.buildflow.purchase.global.exception.ErrorCode;
import com.buildflow.purchase.infra.feign.SiteReferenceClient;
import com.buildflow.purchase.infra.feign.SiteReferenceResponse;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SiteReferenceValidator {

    private final SiteReferenceClient siteReferenceClient;

    public void requireExistingSite(Long siteId) {
        if (siteId == null || siteId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_SITE_REFERENCE);
        }

        SiteReferenceResponse response;
        try {
            response = siteReferenceClient.findById(siteId);
        } catch (FeignException.NotFound e) {
            throw new BusinessException(ErrorCode.INVALID_SITE_REFERENCE);
        } catch (FeignException e) {
            throw new BusinessException(ErrorCode.SITE_REFERENCE_UNAVAILABLE);
        }

        if (response == null || !response.success() || response.data() == null
                || !siteId.equals(response.data().id())) {
            throw new BusinessException(ErrorCode.SITE_REFERENCE_UNAVAILABLE);
        }
        if (response.data().archived()) {
            throw new BusinessException(ErrorCode.SITE_ARCHIVED);
        }
    }

    /**
     * 기존 자료의 수정·삭제 전 검사. 보관된 현장이면 409, 현장 확인 장애는 503으로 실패-닫힘.
     * 현장 검증 도입 전 생긴 고아 자료(현장 404)는 정정할 수 있도록 막지 않는다.
     */
    public void requireSiteNotArchived(Long siteId) {
        if (siteId == null) {
            return;
        }
        SiteReferenceResponse response;
        try {
            response = siteReferenceClient.findById(siteId);
        } catch (FeignException.NotFound e) {
            return;
        } catch (FeignException e) {
            throw new BusinessException(ErrorCode.SITE_REFERENCE_UNAVAILABLE);
        }
        if (response == null || !response.success() || response.data() == null
                || !siteId.equals(response.data().id())) {
            throw new BusinessException(ErrorCode.SITE_REFERENCE_UNAVAILABLE);
        }
        if (response.data().archived()) {
            throw new BusinessException(ErrorCode.SITE_ARCHIVED_DOCUMENT_LOCKED);
        }
    }
}
