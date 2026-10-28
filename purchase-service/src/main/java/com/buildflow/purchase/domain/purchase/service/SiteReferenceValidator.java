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
     * 새 문서 생성 경로에서 엔티티·outbox를 저장·flush한 뒤 커밋 직전에 다시 확인한다.
     * 첫 검증과 저장 사이에 현장이 보관·삭제되면 같은 예외(400/503)를 던져 트랜잭션 전체를 롤백한다(fail-closed).
     * 현장 DB가 달라 행 잠금으로 직렬화할 수 없으므로, 재확인과 커밋 사이의 짧은 구간만 남는다.
     */
    public void recheckBeforeCommit(Long siteId) {
        requireExistingSite(siteId);
    }

    /**
     * 기존 문서의 수정·삭제·확정 경로에서 변경·outbox 기록을 flush한 뒤 커밋 직전에 보관 여부를 다시 확인한다.
     * 첫 검사 직후 현장이 보관되면 409(SITE_ARCHIVED_DOCUMENT_LOCKED), 확인 장애면 503을 던져 변경 전체를 롤백한다.
     * 현장 404(고아 자료)는 첫 검사와 같이 막지 않는다.
     */
    public void recheckNotArchivedBeforeCommit(Long siteId) {
        requireSiteNotArchived(siteId);
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
