package com.buildflow.notification.domain.warranty.entity;

/** 보증 행의 값이 바뀐 이유. 감사 이력은 추가만 하고 고치지 않는다. */
public enum WarrantyAuditAction {
    CREATED,
    UPLOADED,
    OCR_APPLIED,
    OCR_FAILED,
    MANUAL_UPDATED,
    DELETED
}
