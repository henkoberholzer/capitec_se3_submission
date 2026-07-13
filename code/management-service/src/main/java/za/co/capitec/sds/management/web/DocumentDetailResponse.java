package za.co.capitec.sds.management.web;

import za.co.capitec.sds.management.domain.AuditEvent;
import za.co.capitec.sds.management.domain.Document;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DocumentDetailResponse(
        UUID documentId,
        String status,
        long fileSizeBytes,
        String sha256Hash,
        int maxDownloads,
        int downloadCount,
        Instant expiresAt,
        Instant createdAt,
        String createdBy,
        List<AuditEventResponse> auditEvents
) {
    public record AuditEventResponse(String eventType, Instant occurredAt, String result, String reasonCode, String callerId) {}

    public static DocumentDetailResponse from(Document doc, List<AuditEvent> events) {
        var auditResponses = events.stream()
                .map(e -> new AuditEventResponse(e.getEventType(), e.getOccurredAt(), e.getResult(), e.getReasonCode(), e.getCallerId()))
                .toList();
        return new DocumentDetailResponse(
                doc.getId(), doc.getStatus().persistenceValue(), doc.getFileSizeBytes(),
                doc.getSha256Hash(), doc.getMaxDownloads(), doc.getDownloadCount(),
                doc.getExpiresAt(), doc.getCreatedAt(), doc.getCreatedBy(), auditResponses);
    }
}
