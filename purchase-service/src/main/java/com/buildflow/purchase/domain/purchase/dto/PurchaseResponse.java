package com.buildflow.purchase.domain.purchase.dto;

import com.buildflow.purchase.domain.purchase.entity.Purchase;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
public class PurchaseResponse {

    private Long id;
    private Long siteId;
    private String itemName;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal totalAmount;
    private String supplier;
    private LocalDate purchaseDate;
    private String memo;
    /** 증빙 매입 세금계산서 ID. 미연결이면 null. */
    private Long evidenceTaxInvoiceId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static PurchaseResponse from(Purchase purchase) {
        return PurchaseResponse.builder()
                .id(purchase.getId())
                .siteId(purchase.getSiteId())
                .itemName(purchase.getItemName())
                .quantity(purchase.getQuantity())
                .unitPrice(purchase.getUnitPrice())
                .totalAmount(purchase.getTotalAmount())
                .supplier(purchase.getSupplier())
                .purchaseDate(purchase.getPurchaseDate())
                .memo(purchase.getMemo())
                .evidenceTaxInvoiceId(purchase.getEvidenceTaxInvoiceId())
                .createdAt(purchase.getCreatedAt())
                .updatedAt(purchase.getUpdatedAt())
                .build();
    }
}
