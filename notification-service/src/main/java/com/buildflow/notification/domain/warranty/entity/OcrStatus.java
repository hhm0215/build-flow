package com.buildflow.notification.domain.warranty.entity;

public enum OcrStatus {
    /** OCR 처리 대기/진행 중 */
    PENDING,
    /** OCR 추출 완료 — 필수 필드가 채워졌으나 사용자가 아직 확인하지 않음 */
    SUCCESS,
    /** OCR 실패 — 파일 손상, Tesseract 에러 등. 사용자가 수동 입력으로 보완 */
    FAILED,
    /** 사용자가 직접 입력하거나 OCR 추출값을 확인·수정하여 저장함 */
    MANUAL
}
