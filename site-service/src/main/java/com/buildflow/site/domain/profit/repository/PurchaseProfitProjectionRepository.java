package com.buildflow.site.domain.profit.repository;

import com.buildflow.site.domain.profit.entity.PurchaseProfitProjection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseProfitProjectionRepository
        extends JpaRepository<PurchaseProfitProjection, Long> {
}
