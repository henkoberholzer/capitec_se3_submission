package za.co.capitec.sds.management.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import za.co.capitec.sds.management.domain.AuditEvent;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class AuditEventRepository {

    private final JdbcClient jdbc;

    public AuditEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void save(AuditEvent event) {
        jdbc.sql("""
                INSERT INTO audit_events
                    (document_id, event_type, occurred_at, result, reason_code, caller_id)
                VALUES
                    (:documentId, :eventType, :occurredAt, :result, :reasonCode, :callerId)
                """)
                .param("documentId", event.getDocumentId())
                .param("eventType", event.getEventType())
                .param("occurredAt", Timestamp.from(event.getOccurredAt()))
                .param("result", event.getResult())
                .param("reasonCode", event.getReasonCode())
                .param("callerId", event.getCallerId())
                .update();
    }

    public List<AuditEvent> findByDocumentIdOrderByOccurredAtAsc(UUID documentId) {
        return jdbc.sql("""
                SELECT * FROM audit_events
                WHERE document_id = :documentId
                ORDER BY occurred_at ASC
                """)
                .param("documentId", documentId)
                .query(AuditEvent::mapRow)
                .list();
    }

    public void deleteByDocumentId(UUID documentId) {
        jdbc.sql("DELETE FROM audit_events WHERE document_id = :documentId")
                .param("documentId", documentId)
                .update();
    }
}
