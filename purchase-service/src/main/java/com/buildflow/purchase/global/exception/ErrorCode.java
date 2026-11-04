package com.buildflow.purchase.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Purchase
    PURCHASE_NOT_FOUND(HttpStatus.NOT_FOUND, "매입 내역을 찾을 수 없습니다."),
    INVALID_SITE_REFERENCE(HttpStatus.BAD_REQUEST, "유효한 현장을 찾을 수 없습니다."),
    SITE_REFERENCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "현장 확인 서비스를 사용할 수 없습니다."),
    SITE_ARCHIVED_DOCUMENT_LOCKED(HttpStatus.CONFLICT, "보관된 현장의 자료는 현장을 복원한 뒤 수정·삭제할 수 있습니다."),
    SITE_ARCHIVED(HttpStatus.BAD_REQUEST, "보관된 현장에는 새 자료를 등록할 수 없습니다. 현장을 복원한 뒤 등록해 주세요."),
    INVALID_PURCHASE_AMOUNT(HttpStatus.BAD_REQUEST, "매입 금액이 허용 범위를 벗어났습니다."),
    PURCHASE_SITE_ALREADY_ASSIGNED(HttpStatus.CONFLICT,
            "이미 현장이 배정된 매입입니다. 다른 현장으로 옮기려면 삭제한 뒤 다시 등록해 주세요."),
    PURCHASE_SITE_UNASSIGNED(HttpStatus.BAD_REQUEST, "현장을 먼저 배정한 매입만 증빙을 연결할 수 있습니다."),
    INVALID_PURCHASE_FILTER(HttpStatus.BAD_REQUEST, "현장 필터와 미배정 필터는 함께 쓸 수 없습니다."),
    PROFIT_SOURCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "손익 원천 정보를 확인할 수 없습니다."),

    // 증빙 세금계산서 연결
    EVIDENCE_TAX_INVOICE_NOT_FOUND(HttpStatus.BAD_REQUEST, "증빙으로 연결할 세금계산서를 찾을 수 없습니다."),
    EVIDENCE_TAX_INVOICE_NOT_PURCHASE(HttpStatus.BAD_REQUEST, "매입 세금계산서만 매입 증빙으로 연결할 수 있습니다."),
    EVIDENCE_TAX_INVOICE_SITE_MISMATCH(HttpStatus.BAD_REQUEST, "같은 현장의 세금계산서만 증빙으로 연결할 수 있습니다."),
    TAX_INVOICE_REFERENCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "세금계산서 확인 서비스를 사용할 수 없습니다.");

    private final HttpStatus status;
    private final String message;
}
