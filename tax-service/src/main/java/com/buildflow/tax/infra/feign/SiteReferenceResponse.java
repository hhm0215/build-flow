package com.buildflow.tax.infra.feign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SiteReferenceResponse(boolean success, SiteIdentity data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SiteIdentity(Long id) {
    }
}
