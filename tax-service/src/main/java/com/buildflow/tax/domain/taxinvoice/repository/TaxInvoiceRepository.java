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

    /**
     * "입금 확인"인데 유효 입금 합계가 총액에 못 미치는 계산서 — 입금 내역 도입 전 확인 건(행 없음)과,
     * 이전 코드로 되돌린 동안 부분 입금 뒤 입금 확인된 건(일부 행만 있음)을 함께 찾는다.
     */
    @Query("SELECT t.id FROM TaxInvoice t WHERE t.paymentConfirmed = true AND t.totalAmount > 0 AND t.totalAmount > "
            + "(SELECT COALESCE(SUM(p.amount), 0) FROM TaxPayment p WHERE p.taxInvoiceId = t.id AND p.canceledAt IS NULL) "
            + "ORDER BY t.id")
    List<Long> findConfirmedIdsWithUnrecordedBalance();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TaxInvoice t WHERE t.id = :id")
    Optional<TaxInvoice> findByIdForUpdate(@Param("id") Long id);
}
