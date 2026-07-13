package za.co.capitec.sds.management.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import za.co.capitec.sds.management.domain.Document;
import za.co.capitec.sds.management.domain.DocumentStatus;
import za.co.capitec.sds.management.domain.OutboxEvent;
import za.co.capitec.sds.management.domain.OutboxStatus;
import za.co.capitec.sds.management.outbox.OutboxEventHandler;
import za.co.capitec.sds.management.outbox.OutboxProcessor;
import za.co.capitec.sds.management.outbox.handlers.ArchiveDocumentEventHandler;
import za.co.capitec.sds.management.outbox.handlers.RemoveArchivedDocumentEventHandler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres proof for outbox lease claim, SKIP LOCKED, retry/fail, and CREATING cleanup.
 */
class OutboxIntegrationTest extends PostgresIntegrationSupport {

    private static final Duration LEASE = OutboxProcessor.LEASE_DURATION;

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentClaimBatch_underOpenTransactions_areDisjoint() throws Exception {
        UUID aggregate = UUID.randomUUID();
        Instant now = Instant.now();
        for (int i = 0; i < 6; i++) {
            outboxEventRepository.save(OutboxEvent.create(aggregate, now.minusSeconds(1), "TYPE_A", 5));
        }

        List<Long> batch1 = Collections.synchronizedList(new ArrayList<>());
        List<Long> batch2 = Collections.synchronizedList(new ArrayList<>());
        CyclicBarrier claimed = new CyclicBarrier(3);
        CyclicBarrier release = new CyclicBarrier(3);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> w1 = pool.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            List<OutboxEvent> events = outboxEventRepository.claimBatch(now, LEASE, 3);
            events.forEach(e -> batch1.add(e.getId()));
            await(claimed);
            await(release);
        }));
        Future<?> w2 = pool.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            List<OutboxEvent> events = outboxEventRepository.claimBatch(now, LEASE, 3);
            events.forEach(e -> batch2.add(e.getId()));
            await(claimed);
            await(release);
        }));

        await(claimed);
        assertThat(batch1).hasSize(3);
        assertThat(batch2).hasSize(3);

        Set<Long> intersection = new HashSet<>(batch1);
        intersection.retainAll(new HashSet<>(batch2));
        assertThat(intersection).isEmpty();

        await(release);
        w1.get(5, TimeUnit.SECONDS);
        w2.get(5, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void claimBatch_afterCommit_notVisibleUntilLeaseExpires() {
        UUID aggregate = UUID.randomUUID();
        Instant now = Instant.now();
        outboxEventRepository.save(OutboxEvent.create(aggregate, now.minusSeconds(1), "TYPE_A", 5));

        List<OutboxEvent> first = transactionTemplate.execute(status ->
                outboxEventRepository.claimBatch(now, Duration.ofMinutes(2), 10));
        assertThat(first).hasSize(1);
        assertThat(first.get(0).getAttemptCount()).isEqualTo(1);
        assertThat(first.get(0).getLockedUntil()).isAfter(now);

        // After claim TX commits, row still PENDING but leased — second claim must not see it
        List<OutboxEvent> second = transactionTemplate.execute(status ->
                outboxEventRepository.claimBatch(now.plusSeconds(1), Duration.ofMinutes(2), 10));
        assertThat(second).isEmpty();

        // After lease expiry, reclaimable
        Instant afterLease = first.get(0).getLockedUntil().plusSeconds(1);
        List<OutboxEvent> third = transactionTemplate.execute(status ->
                outboxEventRepository.claimBatch(afterLease, Duration.ofMinutes(2), 10));
        assertThat(third).hasSize(1);
        assertThat(third.get(0).getAttemptCount()).isEqualTo(2);
    }

    @Test
    void processBatch_successfulHandler_deletesOutboxRow() {
        Instant now = Instant.now();
        outboxEventRepository.save(OutboxEvent.create(UUID.randomUUID(), now.minusSeconds(1), "TEST_OK", 5));

        AtomicInteger handled = new AtomicInteger();
        processBatch(now, List.of(handler("TEST_OK", e -> handled.incrementAndGet())));

        assertThat(handled.get()).isEqualTo(1);
        Integer remaining = jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'TEST_OK'")
                .query(Integer.class)
                .single();
        assertThat(remaining).isZero();
    }

    @Test
    void processBatch_failingHandler_underMax_schedulesRetry() {
        Instant now = Instant.now();
        outboxEventRepository.save(OutboxEvent.create(UUID.randomUUID(), now.minusSeconds(1), "TEST_FAIL", 3));

        processBatch(now, List.of(handler("TEST_FAIL", e -> {
            throw new RuntimeException("boom");
        })));

        String status = jdbc.sql("SELECT status FROM outbox_events WHERE event_type = 'TEST_FAIL'")
                .query(String.class)
                .single();
        assertThat(status).isEqualTo(OutboxStatus.PENDING.name());

        Integer attempts = jdbc.sql("SELECT attempt_count FROM outbox_events WHERE event_type = 'TEST_FAIL'")
                .query(Integer.class)
                .single();
        assertThat(attempts).isEqualTo(1);

        Instant processAfter = jdbc.sql("SELECT process_after FROM outbox_events WHERE event_type = 'TEST_FAIL'")
                .query((rs, n) -> rs.getTimestamp(1).toInstant())
                .single();
        assertThat(processAfter).isAfter(now);

        Boolean unlocked = jdbc.sql(
                        "SELECT (locked_until IS NULL) FROM outbox_events WHERE event_type = 'TEST_FAIL'")
                .query(Boolean.class)
                .single();
        assertThat(unlocked).isTrue();
    }

    @Test
    void processBatch_failingHandler_atMaxAttempts_marksFailed() {
        Instant now = Instant.now();
        OutboxEvent event = OutboxEvent.create(UUID.randomUUID(), now.minusSeconds(1), "TEST_FAIL_MAX", 1);
        outboxEventRepository.save(event);

        processBatch(now, List.of(handler("TEST_FAIL_MAX", e -> {
            throw new RuntimeException("boom");
        })));

        String status = jdbc.sql("SELECT status FROM outbox_events WHERE event_type = 'TEST_FAIL_MAX'")
                .query(String.class)
                .single();
        assertThat(status).isEqualTo(OutboxStatus.FAILED.name());
    }

    @Test
    void processBatch_creatingDocument_archiveSkipped_removeCleansRow() {
        Instant now = Instant.now();
        UUID docId = UUID.randomUUID();
        Document creating = new Document();
        creating.setId(docId);
        creating.setTokenHash("creating-hash");
        creating.setStorageKey("documents/" + docId + "/payload");
        creating.setFileSizeBytes(0);
        creating.setSha256Hash("");
        creating.setMaxDownloads(1);
        creating.setDownloadCount(0);
        creating.setExpiresAt(now.plusSeconds(60));
        creating.setCreatedBy("it");
        creating.setStatus(new DocumentStatus.Creating());
        documentRepository.save(creating);

        outboxEventRepository.save(OutboxEvent.create(
                docId, now.minusSeconds(1), ArchiveDocumentEventHandler.EVENT_TYPE, 5));
        outboxEventRepository.save(OutboxEvent.create(
                docId, now.minusSeconds(1), RemoveArchivedDocumentEventHandler.EVENT_TYPE, 5));

        OutboxEventHandler archive = handler(ArchiveDocumentEventHandler.EVENT_TYPE, event -> {
            Document doc = documentRepository.findById(event.getAggregateId()).orElse(null);
            if (doc != null && doc.getStatus() instanceof DocumentStatus.Creating) {
                return;
            }
            throw new IllegalStateException("unexpected archive target");
        });
        OutboxEventHandler remove = handler(RemoveArchivedDocumentEventHandler.EVENT_TYPE, event -> {
            Document doc = documentRepository.findById(event.getAggregateId()).orElse(null);
            if (doc == null) {
                return;
            }
            if (!(doc.getStatus() instanceof DocumentStatus.Creating
                    || doc.getStatus() instanceof DocumentStatus.Archived)) {
                throw new IllegalStateException("not removable: " + doc.getStatus());
            }
            documentRepository.deleteById(doc.getId());
        });

        processBatch(now, List.of(archive, remove));

        assertThat(documentRepository.findById(docId)).isEmpty();
        Integer outboxLeft = jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = :id")
                .param("id", docId)
                .query(Integer.class)
                .single();
        assertThat(outboxLeft).isZero();
    }

    private void processBatch(Instant now, List<OutboxEventHandler> handlers) {
        List<OutboxEvent> events = transactionTemplate.execute(status ->
                outboxEventRepository.claimBatch(now, LEASE, 50));
        if (events == null || events.isEmpty()) {
            return;
        }

        var byType = handlers.stream()
                .collect(java.util.stream.Collectors.toMap(OutboxEventHandler::eventType, h -> h));

        List<OutboxEvent> success = new ArrayList<>();
        List<OutboxEvent> failed = new ArrayList<>();
        for (OutboxEvent event : events) {
            try {
                OutboxEventHandler handler = byType.get(event.getEventType());
                if (handler == null) {
                    throw new IllegalStateException("No handler for " + event.getEventType());
                }
                handler.handle(event);
                success.add(event);
            } catch (Exception e) {
                failed.add(event);
            }
        }

        Instant finalizeNow = Instant.now();
        transactionTemplate.executeWithoutResult(status -> {
            for (OutboxEvent e : success) {
                outboxEventRepository.delete(e.getId());
            }
            for (OutboxEvent e : failed) {
                if (e.getAttemptCount() >= e.getMaxAttempts()) {
                    outboxEventRepository.markFailed(e.getId());
                } else {
                    outboxEventRepository.scheduleRetry(
                            e.getId(), finalizeNow.plus(OutboxProcessor.backoffDelay(e.getAttemptCount())));
                }
            }
        });
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static OutboxEventHandler handler(String type, Consumer<OutboxEvent> body) {
        return new OutboxEventHandler() {
            @Override
            public String eventType() {
                return type;
            }

            @Override
            public void handle(OutboxEvent event) {
                body.accept(event);
            }
        };
    }

}
