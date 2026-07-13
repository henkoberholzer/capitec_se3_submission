package za.co.capitec.sds.management.domain;

import lombok.Getter;
import lombok.Setter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class AuditEvent {

    private Long id;
    private UUID documentId;
    private String eventType;
    private Instant occurredAt = Instant.now();
    private String result;
    private String reasonCode;
    private String callerId;

    private AuditEvent() {
    }

    public static AuditEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        AuditEvent e = new AuditEvent();
        e.id = rs.getLong("id");
        e.documentId = rs.getObject("document_id", UUID.class);
        e.eventType = rs.getString("event_type");
        e.occurredAt = rs.getTimestamp("occurred_at").toInstant();
        e.result = rs.getString("result");
        e.reasonCode = rs.getString("reason_code");
        e.callerId = rs.getString("caller_id");
        return e;
    }

    public static AuditEvent success(UUID documentId, String eventType, String callerId) {
        AuditEvent e = new AuditEvent();
        e.documentId = documentId;
        e.eventType = eventType;
        e.result = "SUCCESS";
        e.callerId = callerId;
        return e;
    }

    public static AuditEvent failure(UUID documentId, String eventType, String reasonCode) {
        AuditEvent e = new AuditEvent();
        e.documentId = documentId;
        e.eventType = eventType;
        e.result = "FAILURE";
        e.reasonCode = reasonCode;
        return e;
    }
}
