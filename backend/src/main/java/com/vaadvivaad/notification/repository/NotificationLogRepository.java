package com.vaadvivaad.notification.repository;

import com.vaadvivaad.notification.entity.NotificationLog;
import com.vaadvivaad.notification.entity.NotificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {

    List<NotificationLog> findByStatus(NotificationStatus status);

    List<NotificationLog> findByUserId(UUID userId);
}