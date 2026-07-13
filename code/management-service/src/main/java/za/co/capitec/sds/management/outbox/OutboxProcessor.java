package za.co.capitec.sds.management.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.capitec.sds.management.domain.OutboxEvent;
import za.co.capitec.sds.management.repository.OutboxEventRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Polls the transactional outbox: lease a batch, run handlers, delete or retry/fail.
 * <p>
 * Lease length covers slow I/O (Kafka/S3) without holding a DB transaction open.
 * On failure, exponential backoff updates {@code process_after}; after {@code max_attempts}
 * claims the row is marked {@code FAILED}.
 */
@Slf4j
@Component
public class OutboxProcessor {

    /** How long a claimed row is invisible to other pollers (app clock). */
    public static final Duration LEASE_DURATION = Duration.ofMinutes(2);

    private static final Duration BACKOFF_BASE = Duration.ofSeconds(5);
    private static final Duration BACKOFF_CAP = Duration.ofMinutes(5);

    private final OutboxEventRepository outboxEventRepository;
    private final Map<String, OutboxEventHandler> handlers;
    private final TransactionTemplate transactionTemplate;

    public OutboxProcessor(
            final OutboxEventRepository outboxEventRepository,
            final List<OutboxEventHandler> handlers,
            final PlatformTransactionManager transactionManager
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.handlers = handlers.stream()
                .collect(java.util.stream.Collectors.toMap(OutboxEventHandler::eventType, Function.identity()));
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void pollAndProcess(int batchSize) {
        Instant now = Instant.now();
        List<OutboxEvent> events = transactionTemplate.execute(status ->
                outboxEventRepository.claimBatch(now, LEASE_DURATION, batchSize));

        if (events == null || events.isEmpty()) {
            return;
        }
        log.info("event=OUTBOX_POLL count={}", events.size());

        List<EventProcessingResult> results = new ArrayList<>();
        for (OutboxEvent event : events) {
            try {
                processEvent(event);
                results.add(new EventProcessingResult(event, true));
            } catch (Exception e) {
                log.error("event=OUTBOX_PROCESS_FAILED outboxId={} attempt={}/{}",
                        event.getId(), event.getAttemptCount(), event.getMaxAttempts(), e);
                results.add(new EventProcessingResult(event, false));
            }
        }

        Instant finalizeNow = Instant.now();
        transactionTemplate.executeWithoutResult(status -> finalizeResults(results, finalizeNow));
    }

    private void finalizeResults(List<EventProcessingResult> results, Instant now) {
        for (EventProcessingResult result : results) {
            if (result.success()) {
                outboxEventRepository.delete(result.event().getId());
            } else {
                recordFailure(result.event(), now);
            }
        }
    }

    private void recordFailure(OutboxEvent event, Instant now) {
        if (event.getAttemptCount() >= event.getMaxAttempts()) {
            outboxEventRepository.markFailed(event.getId());
            log.warn("event=OUTBOX_EXHAUSTED outboxId={} attempts={}",
                    event.getId(), event.getAttemptCount());
            return;
        }
        Instant nextAttempt = now.plus(backoffDelay(event.getAttemptCount()));
        outboxEventRepository.scheduleRetry(event.getId(), nextAttempt);
        log.info("event=OUTBOX_RETRY_SCHEDULED outboxId={} attempt={}/{} processAfter={}",
                event.getId(), event.getAttemptCount(), event.getMaxAttempts(), nextAttempt);
    }

    /**
     * Exponential backoff from post-claim attempt count: 5s, 10s, 20s, … capped at 5 minutes.
     */
    public static Duration backoffDelay(int attemptCount) {
        int exp = Math.max(0, Math.min(attemptCount - 1, 6));
        long seconds = BACKOFF_BASE.multipliedBy(1L << exp).toSeconds();
        long capped = Math.min(seconds, BACKOFF_CAP.toSeconds());
        return Duration.ofSeconds(capped);
    }

    private void processEvent(OutboxEvent event) {
        OutboxEventHandler handler = handlers.get(event.getEventType());

        if (handler == null) {
            log.atError()
                    .addKeyValue("outboxEventId", event.getId())
                    .addKeyValue("eventType", event.getEventType())
                    .log("No handler registered for outbox event type");
            throw new OutboxProcessingException("No handler for event type: " + event.getEventType());
        }

        handler.handle(event);
    }

    private record EventProcessingResult(OutboxEvent event, boolean success) {}

    private static class OutboxProcessingException extends RuntimeException {
        OutboxProcessingException(String message) {
            super(message);
        }
    }
}
