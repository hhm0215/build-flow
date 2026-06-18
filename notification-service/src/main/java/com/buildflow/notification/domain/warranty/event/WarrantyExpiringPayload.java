package com.buildflow.notification.domain.warranty.event;

import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarrantyExpiringPayload {

    private Long warrantyId;
    private Long siteId;
    private String insuranceCompany;
    private LocalDate endDate;
    private long daysUntilExpiry;

    public static WarrantyExpiringPayload of(DefectWarranty warranty, long daysUntilExpiry) {
        return WarrantyExpiringPayload.builder()
                .warrantyId(warranty.getId())
                .siteId(warranty.getSiteId())
                .insuranceCompany(warranty.getInsuranceCompany())
                .endDate(warranty.getEndDate())
                .daysUntilExpiry(daysUntilExpiry)
                .build();
    }
}
