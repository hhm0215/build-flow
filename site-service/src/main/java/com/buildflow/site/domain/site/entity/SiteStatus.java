package com.buildflow.site.domain.site.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SiteStatus {
    /** 수주 전·견적 중. 견적이 계약보다 먼저인 일을 담는다(수주 시 IN_PROGRESS로 전환, 실패는 보관). */
    BIDDING("수주 전"),
    IN_PROGRESS("시공 중"),
    SETTLING("정산 중"),
    WARRANTY("하자보증"),
    COMPLETED("완료");

    /** 화면·AI 프롬프트용 한국어 라벨. 저장값은 enum 이름 그대로다. */
    private final String label;

    /** 저장된 이름을 라벨로 바꾼다. 모르는 값은 원문을 그대로 돌려준다. */
    public static String labelOf(String name) {
        for (SiteStatus status : values()) {
            if (status.name().equals(name)) {
                return status.label;
            }
        }
        return name;
    }

    /** 새 현장의 시작 상태로 허용되는지. 정산·보증·완료로 바로 등록하지 않는다. */
    public boolean isInitial() {
        return this == BIDDING || this == IN_PROGRESS;
    }

    /**
     * BIDDING은 IN_PROGRESS(수주)로만 나가고, 수주 이후 상태는 BIDDING으로 돌아오지 않는다.
     * 그 밖의 수주 이후 상태 간 이동은 기존처럼 자유롭다. 같은 상태 유지는 항상 허용한다.
     */
    public boolean canTransitionTo(SiteStatus next) {
        if (next == null) {
            return false;
        }
        if (this == next) {
            return true;
        }
        if (this == BIDDING) {
            return next == IN_PROGRESS;
        }
        return next != BIDDING;
    }
}
