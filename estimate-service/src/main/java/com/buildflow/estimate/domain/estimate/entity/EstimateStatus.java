package com.buildflow.estimate.domain.estimate.entity;

public enum EstimateStatus {
    DRAFT,      // 임시저장
    CONFIRMED,  // 확정 — 손익에 반영
    SUPERSEDED  // 같은 수정 체인의 새 확정본으로 대체됨 — 이력만 보존, 손익에서 제외
}
