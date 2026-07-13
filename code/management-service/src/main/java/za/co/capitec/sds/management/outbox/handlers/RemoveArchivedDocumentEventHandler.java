package za.co.capitec.sds.management.outbox.handlers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import za.co.capitec.sds.management.domain.Document;
import za.co.capitec.sds.management.domain.DocumentStatus;
import za.co.capitec.sds.management.domain.OutboxEvent;
import za.co.capitec.sds.management.outbox.OutboxEventHandler;
import za.co.capitec.sds.management.repository.AuditEventRepository;
import za.co.capitec.sds.management.repository.DocumentRepository;

@Slf4j
@Component
@RequiredArgsConstructor
public class RemoveArchivedDocumentEventHandler implements OutboxEventHandler {

    public static final String EVENT_TYPE = "REMOVE_ARCHIVED_DOCUMENT";

    private final DocumentRepository documentRepository;
    private final AuditEventRepository auditEventRepository;

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

        // Normal path: metadata removed only after audit archive (ARCHIVED).
        // Incomplete upload: CREATING never archived — still drop operational rows.
        boolean removable = doc.getStatus() instanceof DocumentStatus.Archived
                || doc.getStatus() instanceof DocumentStatus.Creating;

        if (!removable) {
            log.error("event=REMOVE_DOCUMENT_FAILED outboxId={} documentId={} status={} reason=NOT_REMOVABLE",
                    event.getId(), doc.getId(), doc.getStatus().persistenceValue());
            throw new IllegalStateException(
                    "Cannot remove document " + doc.getId() + ": expected ARCHIVED or CREATING but was "
                            + doc.getStatus().persistenceValue());
        }

        auditEventRepository.deleteByDocumentId(doc.getId());
        documentRepository.deleteById(doc.getId());
        log.info("event=REMOVE_DOCUMENT_SUCCESS outboxId={} documentId={} priorStatus={}",
                event.getId(), doc.getId(), doc.getStatus().persistenceValue());
    }
}
