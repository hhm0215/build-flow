package com.buildflow.estimate.global.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    /** 손익 이벤트를 같은 스냅샷에서 읽고 record_key를 정확한 문자열로 분류한다. */
    @Query("select o from OutboxEvent o where o.topic in :topics order by o.createdAt, o.eventId")
    List<OutboxEvent> findProfitEvents(@Param("topics") Collection<String> topics);

    @Query(value = """
            SELECT * FROM outbox_events
            WHERE (status = 'PENDING' AND next_attempt_at <= :now)
               OR (status = 'CLAIMED' AND lease_until <= :now)
            ORDER BY created_at, event_id
            LIMIT 1 FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<OutboxEvent> lockNextAvailable(@Param("now") Instant now);

    @Modifying
    @Query(value = """
            UPDATE outbox_events
               SET status = 'SENT', sent_at = :now, claim_token = NULL,
                   lease_until = NULL, last_error = NULL
             WHERE event_id = :eventId AND status = 'CLAIMED' AND claim_token = :token
            """, nativeQuery = true)
    int markSentIfClaimed(@Param("eventId") String eventId,
                          @Param("token") String token, @Param("now") Instant now);

    @Modifying
    @Query(value = """
            UPDATE outbox_events
               SET status = 'PENDING', attempts = attempts + 1,
                   next_attempt_at = :nextAttemptAt, claim_token = NULL,
                   lease_until = NULL, last_error = :error
             WHERE event_id = :eventId AND status = 'CLAIMED' AND claim_token = :token
            """, nativeQuery = true)
    int markFailedIfClaimed(@Param("eventId") String eventId,
                            @Param("token") String token,
                            @Param("nextAttemptAt") Instant nextAttemptAt,
                            @Param("error") String error);
}
