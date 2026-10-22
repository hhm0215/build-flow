package com.buildflow.purchase.domain.purchase.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 현장별 매입 항목 ↔ 매입 세금계산서 증빙 대조. 원가는 매입 항목만이며 이 응답은 누락·중복을 사람이 찾게 돕는다.
 * 차이 = 계산서 금액 − 연결된 매입 합계(양수: 계산서에 비해 연결 매입이 적음, 음수: 연결 매입이 더 많음).
 */
@Getter
@Builder
public class PurchaseEvidenceSummaryResponse {

    private Long siteId;
    private int purchaseCount;
    private BigDecimal purchaseAmount;
    private int linkedPurchaseCount;
    private BigDecimal linkedPurchaseAmount;
    /** 증빙 미연결(끊어진 연결 포함) 매입 건수·금액. */
    private int unlinkedPurchaseCount;
    private BigDecimal unlinkedPurchaseAmount;
    /** 연결된 계산서가 삭제됐거나 더 이상 같은 현장 매입 계산서가 아닌 매입 건수. */
    private int brokenLinkCount;
    private int invoiceCount;
    private BigDecimal invoiceSupplyAmount;
    private BigDecimal invoiceTotalAmount;
    /** 연결된 매입이 0건인 매입 계산서 건수. */
    private int invoiceWithoutPurchaseCount;
    private BigDecimal supplyDifference;
    private BigDecimal totalDifference;
    private List<InvoiceEvidence> invoices;

    @Getter
    @Builder
    public static class InvoiceEvidence {
        private Long taxInvoiceId;
        private String counterparty;
        private LocalDate issueDate;
        private BigDecimal supplyAmount;
        private BigDecimal totalAmount;
        private int linkedPurchaseCount;
        private BigDecimal linkedPurchaseAmount;
        private BigDecimal supplyDifference;
        private BigDecimal totalDifference;
    }
}
