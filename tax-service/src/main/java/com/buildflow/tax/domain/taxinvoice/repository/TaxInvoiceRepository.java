package com.buildflow.tax.domain.taxinvoice.repository;

import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoice;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoiceType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TaxInvoiceRepository extends JpaRepository<TaxInvoice, Long> {

    List<TaxInvoice> findBySiteIdOrderByCreatedAtDesc(Long siteId);

    List<TaxInvoice> findBySiteIdAndTypeOrderByCreatedAtDesc(Long siteId, TaxInvoiceType type);

    List<TaxInvoice> findAllByOrderByCreatedAtDesc();

    List<TaxInvoice> findBySiteIdAndTypeAndPaymentConfirmedFalseOrderByIssueDateAsc(
            Long siteId, TaxInvoiceType type);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TaxInvoice t WHERE t.id = :id")
    Optional<TaxInvoice> findByIdForUpdate(@Param("id") Long id);
}
