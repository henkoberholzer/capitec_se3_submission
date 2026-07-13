package za.co.capitec.sds.management.outbox;

import za.co.capitec.sds.management.domain.OutboxEvent;

public interface OutboxEventHandler {
    String eventType();
    void handle(OutboxEvent event);
}
