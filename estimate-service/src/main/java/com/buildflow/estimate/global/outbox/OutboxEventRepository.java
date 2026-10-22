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

    /** 손익 원천 조회용: 주어진 견적들의 손익 이벤트(상태 무관, record_key = estimateId). */
    @Query("select o from OutboxEvent o where o.topic in :topics and o.recordKey in :recordKeys"
            + " order by o.createdAt, o.eventId")
    List<OutboxEvent> findByTopicInAndRecordKeyIn(@Param("topics") Collection<String> topics,
                                                  @Param("recordKeys") Collection<String> recordKeys);

    /** 손익 원천 조회용: 견적 행이 더는 없는 손익 이벤트(예: 확정 삭제가 허용되던 기간의 estimate.deleted). */
    @Query("select o from OutboxEvent o where o.topic in :topics and not exists"
            + " (select 1 from Estimate e where cast(e.id as String) = o.recordKey)"
            + " order by o.createdAt, o.eventId")
    List<OutboxEvent> findOrphanProfitEvents(@Param("topics") Collection<String> topics);

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
