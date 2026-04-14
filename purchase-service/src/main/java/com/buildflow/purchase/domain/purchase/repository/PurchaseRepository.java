package com.buildflow.purchase.domain.purchase.repository;

import com.buildflow.purchase.domain.purchase.entity.Purchase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PurchaseRepository extends JpaRepository<Purchase, Long> {

    List<Purchase> findBySiteIdOrderByCreatedAtDesc(Long siteId);

    List<Purchase> findAllByOrderByCreatedAtDesc();
}
