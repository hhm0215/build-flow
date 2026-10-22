package com.buildflow.tax.domain.taxinvoice.repository;

import com.buildflow.tax.domain.taxinvoice.entity.TaxPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TaxPaymentRepository extends JpaRepository<TaxPayment, Long> {

    List<TaxPayment> findByTaxInvoiceIdOrderByPaymentDateAscIdAsc(Long taxInvoiceId);

    Optional<TaxPayment> findByIdAndTaxInvoiceId(Long id, Long taxInvoiceId);

    boolean existsByTaxInvoiceId(Long taxInvoiceId);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM TaxPayment p "
            + "WHERE p.taxInvoiceId = :taxInvoiceId AND p.canceledAt IS NULL")
    BigDecimal sumActiveAmount(@Param("taxInvoiceId") Long taxInvoiceId);

    @Query("SELECT MAX(p.paymentDate) FROM TaxPayment p "
            + "WHERE p.taxInvoiceId = :taxInvoiceId AND p.canceledAt IS NULL")
    Optional<LocalDate> findLatestActivePaymentDate(@Param("taxInvoiceId") Long taxInvoiceId);

    @Query("SELECT p.taxInvoiceId AS taxInvoiceId, SUM(p.amount) AS activeAmount FROM TaxPayment p "
            + "WHERE p.taxInvoiceId IN :ids AND p.canceledAt IS NULL GROUP BY p.taxInvoiceId")
    List<ActiveAmount> sumActiveAmounts(@Param("ids") Collection<Long> ids);

    @Query("SELECT DISTINCT p.taxInvoiceId FROM TaxPayment p WHERE p.taxInvoiceId IN :ids")
    List<Long> findInvoiceIdsWithPayments(@Param("ids") Collection<Long> ids);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM TaxPayment p WHERE p.taxInvoiceId = :taxInvoiceId")
    int deleteByTaxInvoiceId(@Param("taxInvoiceId") Long taxInvoiceId);

    interface ActiveAmount {
        Long getTaxInvoiceId();

        BigDecimal getActiveAmount();
    }
}
