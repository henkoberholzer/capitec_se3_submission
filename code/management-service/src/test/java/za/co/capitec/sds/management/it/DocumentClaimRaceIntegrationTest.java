package za.co.capitec.sds.management.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import za.co.capitec.sds.management.domain.Document;
import za.co.capitec.sds.management.domain.DocumentStatus;
import za.co.capitec.sds.management.repository.DownloadClaimResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres proof that concurrent claims cannot overshoot max_downloads,
 * and that failed streams can refund a slot.
 */
class DocumentClaimRaceIntegrationTest extends PostgresIntegrationSupport {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentClaims_withMaxOne_onlyOneSucceeds() throws Exception {
        String tokenHash = "hash-max-one";
        UUID id = insertActiveDocument(tokenHash, 1);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<DownloadClaimResult>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> transactionTemplate.execute(status ->
                    documentRepository.claimDownloadSlot(tokenHash)));
        }

        List<Future<DownloadClaimResult>> futures = pool.invokeAll(tasks);
        pool.shutdown();
        assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        AtomicInteger claimed = new AtomicInteger();
        AtomicInteger notAvailable = new AtomicInteger();
        for (Future<DownloadClaimResult> future : futures) {
            DownloadClaimResult result = future.get(5, TimeUnit.SECONDS);
            if (result instanceof DownloadClaimResult.Claimed) {
                claimed.incrementAndGet();
            } else if (result instanceof DownloadClaimResult.NotAvailable) {
                notAvailable.incrementAndGet();
            }
        }

        assertThat(claimed.get()).isEqualTo(1);
        assertThat(notAvailable.get()).isEqualTo(threads - 1);

        Document after = documentRepository.findById(id).orElseThrow();
        assertThat(after.getDownloadCount()).isEqualTo(1);
        assertThat(after.getStatus()).isInstanceOf(DocumentStatus.Exhausted.class);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentClaims_withMaxTwo_exactlyTwoSucceed() throws Exception {
        String tokenHash = "hash-max-two";
        UUID id = insertActiveDocument(tokenHash, 2);

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<DownloadClaimResult>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> transactionTemplate.execute(status ->
                    documentRepository.claimDownloadSlot(tokenHash)));
        }

        List<Future<DownloadClaimResult>> futures = pool.invokeAll(tasks);
        pool.shutdown();
        assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        long claimed = futures.stream()
                .map(f -> {
                    try {
                        return f.get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .filter(r -> r instanceof DownloadClaimResult.Claimed)
                .count();

        assertThat(claimed).isEqualTo(2);
        Document after = documentRepository.findById(id).orElseThrow();
        assertThat(after.getDownloadCount()).isEqualTo(2);
        assertThat(after.getStatus()).isInstanceOf(DocumentStatus.Exhausted.class);
    }

    @Test
    void releaseDownloadSlot_refundsClaimAndRestoresActiveFromExhausted() {
        String tokenHash = "hash-release";
        UUID id = insertActiveDocument(tokenHash, 1);

        DownloadClaimResult claim = transactionTemplate.execute(status ->
                documentRepository.claimDownloadSlot(tokenHash));
        assertThat(claim).isInstanceOf(DownloadClaimResult.Claimed.class);

        Document exhausted = documentRepository.findById(id).orElseThrow();
        assertThat(exhausted.getDownloadCount()).isEqualTo(1);
        assertThat(exhausted.getStatus()).isInstanceOf(DocumentStatus.Exhausted.class);

        int released = transactionTemplate.execute(status ->
                documentRepository.releaseDownloadSlot(id));
        assertThat(released).isEqualTo(1);

        Document restored = documentRepository.findById(id).orElseThrow();
        assertThat(restored.getDownloadCount()).isEqualTo(0);
        assertThat(restored.getStatus()).isInstanceOf(DocumentStatus.Active.class);

        // Slot usable again
        DownloadClaimResult reclaim = transactionTemplate.execute(status ->
                documentRepository.claimDownloadSlot(tokenHash));
        assertThat(reclaim).isInstanceOf(DownloadClaimResult.Claimed.class);
    }

    @Test
    void claim_whenExpired_returnsNotAvailable() {
        String tokenHash = "hash-expired";
        UUID id = UUID.randomUUID();
        Document doc = baseDocument(id, tokenHash, 3);
        doc.setExpiresAt(Instant.now().minusSeconds(60));
        documentRepository.save(doc);

        DownloadClaimResult result = transactionTemplate.execute(status ->
                documentRepository.claimDownloadSlot(tokenHash));

        assertThat(result).isInstanceOf(DownloadClaimResult.NotAvailable.class);
        assertThat(documentRepository.findById(id).orElseThrow().getDownloadCount()).isZero();
    }

    private UUID insertActiveDocument(String tokenHash, int maxDownloads) {
        UUID id = UUID.randomUUID();
        Document doc = baseDocument(id, tokenHash, maxDownloads);
        documentRepository.save(doc);
        return id;
    }

    private static Document baseDocument(UUID id, String tokenHash, int maxDownloads) {
        Document doc = new Document();
        doc.setId(id);
        doc.setTokenHash(tokenHash);
        doc.setStorageKey("documents/" + id + "/payload");
        doc.setFileSizeBytes(100);
        doc.setSha256Hash("a".repeat(64));
        doc.setMaxDownloads(maxDownloads);
        doc.setDownloadCount(0);
        doc.setExpiresAt(Instant.now().plusSeconds(3600));
        doc.setCreatedBy("it-test");
        doc.setStatus(new DocumentStatus.Active());
        return doc;
    }
}
