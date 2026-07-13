package za.co.capitec.sds.management.outbox.handlers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import za.co.capitec.sds.management.domain.AuditEvent;
import za.co.capitec.sds.management.domain.Document;
import za.co.capitec.sds.management.domain.DocumentStatus;
import za.co.capitec.sds.management.domain.OutboxEvent;
import za.co.capitec.sds.management.outbox.OutboxEventHandler;
import za.co.capitec.sds.management.repository.AuditEventRepository;
import za.co.capitec.sds.management.repository.DocumentRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ArchiveDocumentEventHandler implements OutboxEventHandler {

    public static final String EVENT_TYPE = "ARCHIVE_DOCUMENT";

    private final DocumentRepository documentRepository;
    private final AuditEventRepository auditEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${sds.kafka.topics.document-archive}")
    private String archivedTopic;

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public void handle(OutboxEvent event) {
        Document doc = documentRepository.findById(event.getAggregateId()).orElse(null);
        if (doc == null) {
            return;
        }

        // Incomplete upload: never became ACTIVE — no delivery audit to archive; REMOVE will drop the row.
        if (doc.getStatus() instanceof DocumentStatus.Creating) {
            log.info("event=ARCHIVE_DOCUMENT_SKIPPED outboxId={} documentId={} reason=CREATING",
                    event.getId(), doc.getId());
            return;
        }

        try {
            List<AuditEvent> auditEvents = auditEventRepository.findByDocumentIdOrderByOccurredAtAsc(doc.getId());
            String archivePayload = buildArchivePayload(doc, auditEvents);
            kafkaTemplate.send(archivedTopic, doc.getId().toString(), archivePayload).get(10, TimeUnit.SECONDS);

            doc.setStatus(new DocumentStatus.Archived());
            documentRepository.save(doc);

        } catch (JsonProcessingException e) {
            log.error("event=ARCHIVE_DOCUMENT_FAILED outboxId={}", event.getId(), e);
            throw new RuntimeException(e);
        } catch (Exception e) {
            log.error("event=ARCHIVE_DOCUMENT_FAILED outboxId={}", event.getId(), e);
            throw new RuntimeException(e);
        }
    }

    private String buildArchivePayload(Document doc, List<AuditEvent> auditEvents) throws JsonProcessingException {
        var auditList = auditEvents.stream().map(e -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("eventType", e.getEventType());
            entry.put("occurredAt", e.getOccurredAt().toString());
            entry.put("result", e.getResult());
            entry.put("callerId", e.getCallerId() != null ? e.getCallerId() : "");
            return entry;
        }).toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", doc.getId().toString());
        payload.put("createdAt", doc.getCreatedAt().toString());
        payload.put("expiresAt", doc.getExpiresAt().toString());
        payload.put("revokedAt", doc.getRevokedAt() != null ? doc.getRevokedAt().toString() : "");
        payload.put("status", doc.getStatus().persistenceValue());
        payload.put("fileSizeBytes", doc.getFileSizeBytes());
        payload.put("sha256Hash", doc.getSha256Hash());
        payload.put("tokenHash", doc.getTokenHash());
        payload.put("maxDownloads", doc.getMaxDownloads());
        payload.put("downloadCount", doc.getDownloadCount());
        payload.put("createdBy", doc.getCreatedBy());
        payload.put("auditEvents", auditList);
        return objectMapper.writeValueAsString(payload);
    }
}
