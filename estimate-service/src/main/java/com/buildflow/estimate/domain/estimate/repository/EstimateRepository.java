package com.buildflow.estimate.domain.estimate.repository;

import com.buildflow.estimate.domain.estimate.entity.Estimate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EstimateRepository extends JpaRepository<Estimate, Long> {

    List<Estimate> findBySiteIdOrderByCreatedAtDesc(Long siteId);
}
