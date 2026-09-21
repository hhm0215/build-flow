package com.buildflow.tax.global.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    @Query(value = """
            SELECT * FROM outbox_events
            WHERE (status = 'PENDING' AND next_attempt_at <= :now)
               OR (status = 'CLAIMED' AND lease_until <= :now)
            ORDER BY created_at, event_id
            LIMIT 1 FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<OutboxEvent> findNextClaimable(@Param("now") LocalDateTime now);

    @Modifying
    @Query(value = """
            UPDATE outbox_events SET status = 'SENT', sent_at = :now,
                claim_token = NULL, lease_until = NULL, last_error = NULL
            WHERE event_id = :eventId AND status = 'CLAIMED' AND claim_token = :token
            """, nativeQuery = true)
    int markSent(@Param("eventId") String eventId, @Param("token") String token,
                 @Param("now") LocalDateTime now);

    @Modifying
    @Query(value = """
            UPDATE outbox_events SET status = 'PENDING', next_attempt_at = :nextAttemptAt,
                claim_token = NULL, lease_until = NULL, last_error = :error
            WHERE event_id = :eventId AND status = 'CLAIMED' AND claim_token = :token
            """, nativeQuery = true)
    int markFailed(@Param("eventId") String eventId, @Param("token") String token,
                   @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                   @Param("error") String error);
}
