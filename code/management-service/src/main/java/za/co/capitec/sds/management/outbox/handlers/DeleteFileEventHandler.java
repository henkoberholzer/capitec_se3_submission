package za.co.capitec.sds.management.outbox.handlers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import za.co.capitec.sds.management.domain.OutboxEvent;
import za.co.capitec.sds.management.outbox.OutboxEventHandler;
import za.co.capitec.sds.management.service.StorageService;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteFileEventHandler implements OutboxEventHandler {

    public static final String EVENT_TYPE = "DELETE_FILE";

    private final StorageService storageService;

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public void handle(OutboxEvent event) {
        // Idempotent for missing keys (e.g. CREATING row whose put never succeeded).
        String storageKey = "documents/" + event.getAggregateId() + "/payload";
        log.info("event=DELETE_FILE outboxId={} storageKey={}", event.getId(), storageKey);
        storageService.delete(storageKey);
    }
}
