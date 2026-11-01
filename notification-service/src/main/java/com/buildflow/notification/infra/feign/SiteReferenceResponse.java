package com.buildflow.notification.infra.feign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SiteReferenceResponse(boolean success, SiteIdentity data) {

    /** siteName은 알림 문구 보강용 선택 필드다. 없거나 비어도 참조 검증에는 영향이 없다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SiteIdentity(Long id, Object archivedAt, String siteName) {

        public SiteIdentity(Long id) {
            this(id, null, null);
        }

        public SiteIdentity(Long id, Object archivedAt) {
            this(id, archivedAt, null);
        }

        // 날짜 직렬화 형식과 무관하게 값 존재 여부만 본다
        public boolean archived() {
            return archivedAt != null;
        }
    }
}
