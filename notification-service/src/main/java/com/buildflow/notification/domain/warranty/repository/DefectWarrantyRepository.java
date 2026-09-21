package com.buildflow.notification.domain.warranty.repository;

import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DefectWarrantyRepository extends JpaRepository<DefectWarranty, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM DefectWarranty w WHERE w.id = :id")
    Optional<DefectWarranty> findByIdForUpdate(@Param("id") Long id);

    List<DefectWarranty> findBySiteIdOrderByEndDateAsc(Long siteId);

    List<DefectWarranty> findAllByOrderByEndDateAsc();

    @Query("SELECT w FROM DefectWarranty w WHERE w.endDate BETWEEN :now AND :threshold ORDER BY w.endDate ASC")
    List<DefectWarranty> findExpiringSoon(@Param("now") LocalDate now, @Param("threshold") LocalDate threshold);

    @Query("""
            SELECT w FROM DefectWarranty w
            WHERE w.endDate BETWEEN :today AND :threshold
              AND (w.lastExpiringAlertSentAt IS NULL OR w.lastExpiringAlertSentAt <= :cooldownThreshold)
            ORDER BY w.endDate ASC
            """)
    List<DefectWarranty> findExpiringNotYetAlerted(
            @Param("today") LocalDate today,
            @Param("threshold") LocalDate threshold,
            @Param("cooldownThreshold") LocalDate cooldownThreshold);
}
