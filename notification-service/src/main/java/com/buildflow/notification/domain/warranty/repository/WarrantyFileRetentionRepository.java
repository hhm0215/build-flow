package com.buildflow.notification.domain.warranty.repository;

import com.buildflow.notification.domain.warranty.entity.WarrantyFileRetention;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface WarrantyFileRetentionRepository extends JpaRepository<WarrantyFileRetention, Long> {

    List<WarrantyFileRetention> findTop100ByPurgedAtIsNullAndPurgeAfterLessThanEqualOrderByIdAsc(LocalDate today);
}
