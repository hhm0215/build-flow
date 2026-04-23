package com.buildflow.notification.domain.notification.repository;

import com.buildflow.notification.domain.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findAllByOrderByCreatedAtDesc();

    List<Notification> findByIsReadFalseOrderByCreatedAtDesc();

    long countByIsReadFalse();
}
