package com.buildflow.notification.domain.warranty.entity;

public enum OcrStatus {
    /** OCR 처리 대기/진행 중 */
    PENDING,
    /** OCR 성공 — 추출 필드가 채워졌음 (일부만 채워졌어도 SUCCESS) */
    SUCCESS,
    /** OCR 실패 — 파일 손상, Tesseract 에러 등. 사용자가 수동 입력으로 보완 */
    FAILED,
    /** 사용자가 직접 입력 (OCR 미사용 경로) */
    MANUAL
}
