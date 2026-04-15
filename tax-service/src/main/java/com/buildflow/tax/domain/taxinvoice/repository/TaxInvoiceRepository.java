package com.buildflow.tax.domain.taxinvoice.repository;

import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoice;
import com.buildflow.tax.domain.taxinvoice.entity.TaxInvoiceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaxInvoiceRepository extends JpaRepository<TaxInvoice, Long> {

    List<TaxInvoice> findBySiteIdOrderByCreatedAtDesc(Long siteId);

    List<TaxInvoice> findBySiteIdAndTypeOrderByCreatedAtDesc(Long siteId, TaxInvoiceType type);

    List<TaxInvoice> findAllByOrderByCreatedAtDesc();

    List<TaxInvoice> findBySiteIdAndTypeAndPaymentConfirmedFalseOrderByIssueDateAsc(
            Long siteId, TaxInvoiceType type);
}
