package com.buildflow.site.domain.profit.repository;

import com.buildflow.site.domain.profit.entity.ProcessedProfitEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedProfitEventRepository extends JpaRepository<ProcessedProfitEvent, String> {
}
