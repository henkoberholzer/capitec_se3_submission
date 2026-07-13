package za.co.capitec.sds.management.domain;

import lombok.Getter;
import lombok.Setter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class OutboxEvent {

    private Long id;
    private Instant createdAt;
    private Instant processAfter;
    private Instant lockedUntil;
    private UUID aggregateId;
    private String eventType;
    private OutboxStatus status;
    private int attemptCount;
    private int maxAttempts;

    private OutboxEvent() {}

    public static OutboxEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        OutboxEvent e = new OutboxEvent();
        e.id = rs.getLong("id");
        e.aggregateId = rs.getObject("aggregate_id", UUID.class);
        e.eventType = rs.getString("event_type");
        e.processAfter = rs.getTimestamp("process_after").toInstant();
        var locked = rs.getTimestamp("locked_until");
        e.lockedUntil = locked != null ? locked.toInstant() : null;
        e.status = OutboxStatus.valueOf(rs.getString("status"));
        e.createdAt = rs.getTimestamp("created_at").toInstant();
        e.attemptCount = rs.getInt("attempt_count");
        e.maxAttempts = rs.getInt("max_attempts");
        return e;
    }

    /**
     * @param maxAttempts maximum claim/process attempts before status becomes FAILED (must be &gt;= 1)
     */
    public static OutboxEvent create(
            UUID aggregateId, Instant processAfter, String eventType, int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        OutboxEvent e = new OutboxEvent();
        e.aggregateId = aggregateId;
        e.processAfter = processAfter;
        e.eventType = eventType;
        e.maxAttempts = maxAttempts;
        e.attemptCount = 0;
        e.lockedUntil = null;
        e.status = OutboxStatus.PENDING;
        e.createdAt = Instant.now();
        return e;
    }
}
