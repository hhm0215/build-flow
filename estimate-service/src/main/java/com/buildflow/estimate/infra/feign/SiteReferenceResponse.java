package com.buildflow.estimate.infra.feign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SiteReferenceResponse(boolean success, SiteIdentity data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SiteIdentity(Long id, Object archivedAt) {

        public SiteIdentity(Long id) {
            this(id, null);
        }

        // 날짜 직렬화 형식과 무관하게 값 존재 여부만 본다
        public boolean archived() {
            return archivedAt != null;
        }
    }
}
