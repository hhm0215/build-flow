package com.buildflow.tax.domain.taxinvoice.service;

import com.buildflow.tax.global.exception.BusinessException;
import com.buildflow.tax.global.exception.ErrorCode;
import com.buildflow.tax.infra.feign.SiteReferenceClient;
import com.buildflow.tax.infra.feign.SiteReferenceResponse;
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
    }
}
