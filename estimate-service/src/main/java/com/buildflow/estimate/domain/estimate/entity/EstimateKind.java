package com.buildflow.estimate.domain.estimate.entity;

/**
 * 견적 간 관계 종류. 컬럼 추가 전 기존 행의 NULL은 ORIGINAL로 취급한다.
 * REVISION은 revisionOf 견적을 대체(같은 수정 체인), ADDITIONAL은 별도 금액으로 합산(새 체인).
 */
public enum EstimateKind {
    ORIGINAL,
    REVISION,
    ADDITIONAL
}
