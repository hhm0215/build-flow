package com.buildflow.notification.domain.warranty.repository;

import com.buildflow.notification.domain.warranty.entity.WarrantyAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WarrantyAuditEventRepository extends JpaRepository<WarrantyAuditEvent, Long> {

    List<WarrantyAuditEvent> findByWarrantyIdOrderByOccurredAtAscIdAsc(Long warrantyId);
}
