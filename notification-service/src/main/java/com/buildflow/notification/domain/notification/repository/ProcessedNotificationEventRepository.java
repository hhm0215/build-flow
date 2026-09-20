package com.buildflow.notification.domain.notification.repository;

import com.buildflow.notification.domain.notification.entity.ProcessedNotificationEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedNotificationEventRepository extends JpaRepository<ProcessedNotificationEvent, String> {
}
