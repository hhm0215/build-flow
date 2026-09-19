package com.buildflow.estimate.domain.estimate.repository;

import com.buildflow.estimate.domain.estimate.entity.Estimate;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EstimateRepository extends JpaRepository<Estimate, Long> {

    List<Estimate> findBySiteIdOrderByCreatedAtDesc(Long siteId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Estimate e where e.id = :id")
    Optional<Estimate> findByIdForUpdate(@Param("id") Long id);
}
