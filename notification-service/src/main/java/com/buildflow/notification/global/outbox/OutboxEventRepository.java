package com.buildflow.notification.global.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    boolean existsByTopicAndRecordKeyAndStatusIn(
            String topic, String recordKey, Collection<OutboxStatus> statuses);

    @Query(value = """
            SELECT * FROM outbox_events
            WHERE (status = 'PENDING' AND next_attempt_at <= :now)
               OR (status = 'CLAIMED' AND lease_until <= :now)
            ORDER BY created_at ASC, event_id ASC
            LIMIT 1 FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<OutboxEvent> claimReady(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM OutboxEvent e WHERE e.eventId = :eventId")
    Optional<OutboxEvent> findByIdForUpdate(@Param("eventId") String eventId);
}
