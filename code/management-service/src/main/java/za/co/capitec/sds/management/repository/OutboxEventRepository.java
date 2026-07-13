package za.co.capitec.sds.management.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import za.co.capitec.sds.management.domain.OutboxEvent;
import za.co.capitec.sds.management.domain.OutboxStatus;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Repository
public class OutboxEventRepository {

    private final JdbcClient jdbc;

    public OutboxEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void save(OutboxEvent event) {
        if (event.getId() == null) {
            jdbc.sql("""
                    INSERT INTO outbox_events
                        (aggregate_id, event_type, process_after, status, created_at,
                         locked_until, attempt_count, max_attempts)
                    VALUES
                        (:aggregate_id, :eventType, :processAfter, :status, :createdAt,
                         :lockedUntil, :attemptCount, :maxAttempts)
                    """)
                    .param("aggregate_id", event.getAggregateId())
                    .param("eventType", event.getEventType())
                    .param("processAfter", Timestamp.from(event.getProcessAfter()))
                    .param("status", event.getStatus().name())
                    .param("createdAt", Timestamp.from(event.getCreatedAt()))
                    .param("lockedUntil",
                            event.getLockedUntil() != null ? Timestamp.from(event.getLockedUntil()) : null)
                    .param("attemptCount", event.getAttemptCount())
                    .param("maxAttempts", event.getMaxAttempts())
                    .update();
        } else {
            jdbc.sql("""
                    UPDATE outbox_events SET
                        status = :status,
                        process_after = :processAfter,
                        locked_until = :lockedUntil,
                        attempt_count = :attemptCount,
                        max_attempts = :maxAttempts
                    WHERE id = :id
                    """)
                    .param("id", event.getId())
                    .param("status", event.getStatus().name())
                    .param("processAfter", Timestamp.from(event.getProcessAfter()))
                    .param("lockedUntil",
                            event.getLockedUntil() != null ? Timestamp.from(event.getLockedUntil()) : null)
                    .param("attemptCount", event.getAttemptCount())
                    .param("maxAttempts", event.getMaxAttempts())
                    .update();
        }
    }

    /**
     * Atomically lease a batch of due PENDING events: set {@code locked_until}, increment
     * {@code attempt_count}. Eligible rows are unlocked (null or expired lease) and under
     * their max attempt budget. Uses app-supplied {@code now} (not DB clock).
     */
    public List<OutboxEvent> claimBatch(Instant now, Duration leaseDuration, int limit) {
        Instant lockedUntil = now.plus(leaseDuration);
        return jdbc.sql("""
                WITH cte AS (
                    SELECT id FROM outbox_events
                    WHERE status = 'PENDING'
                      AND process_after <= :now
                      AND (locked_until IS NULL OR locked_until < :now)
                      AND attempt_count < max_attempts
                    ORDER BY process_after
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE outbox_events o
                SET locked_until = :lockedUntil,
                    attempt_count = attempt_count + 1
                FROM cte
                WHERE o.id = cte.id
                RETURNING o.id, o.created_at, o.process_after, o.locked_until, o.aggregate_id,
                          o.event_type, o.status, o.attempt_count, o.max_attempts
                """)
                .param("now", Timestamp.from(now))
                .param("lockedUntil", Timestamp.from(lockedUntil))
                .param("limit", limit)
                .query(OutboxEvent::mapRow)
                .list();
    }

    public void markFailed(final long id) {
        jdbc.sql("""
                UPDATE outbox_events
                SET status = :status,
                    locked_until = NULL
                WHERE id = :id
                """)
                .param("id", id)
                .param("status", OutboxStatus.FAILED.name())
                .update();
    }

    /**
     * Soft-fail: clear lease and push {@code process_after} for backoff retry.
     */
    public void scheduleRetry(final long id, Instant processAfter) {
        jdbc.sql("""
                UPDATE outbox_events
                SET process_after = :processAfter,
                    locked_until = NULL
                WHERE id = :id AND status = 'PENDING'
                """)
                .param("id", id)
                .param("processAfter", Timestamp.from(processAfter))
                .update();
    }

    public void delete(final long id) {
        jdbc.sql("DELETE FROM outbox_events WHERE id = :id")
                .param("id", id)
                .update();
    }
}
