package com.buildflow.purchase.domain.purchase.dto;

/** tax-service가 매입 계산서 삭제·유형 변경 전에 연결 매입 존재 여부를 확인하는 응답. */
public record EvidenceLinkCountResponse(Long taxInvoiceId, long linkedPurchaseCount) {
}
