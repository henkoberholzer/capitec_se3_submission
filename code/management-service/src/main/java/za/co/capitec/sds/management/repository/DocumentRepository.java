package za.co.capitec.sds.management.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import za.co.capitec.sds.management.domain.Document;
import za.co.capitec.sds.management.domain.DocumentStatus;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DocumentRepository {

    private final JdbcClient jdbc;

    public DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Document> findById(UUID id) {
        return jdbc.sql("SELECT * FROM document WHERE id = :id")
                .param("id", id)
                .query(Document::mapRow)
                .optional();
    }

    public Optional<Document> findByTokenHash(String tokenHash) {
        return jdbc.sql("SELECT * FROM document WHERE token_hash = :tokenHash")
                .param("tokenHash", tokenHash)
                .query(Document::mapRow)
                .optional();
    }

    public void save(Document doc) {
        int updated = jdbc.sql("""
                UPDATE document SET
                    token_hash = :tokenHash,
                    storage_key = :storageKey,
                    file_size_bytes = :fileSizeBytes,
                    sha256_hash = :sha256Hash,
                    max_downloads = :maxDownloads,
                    download_count = :downloadCount,
                    expires_at = :expiresAt,
                    created_by = :createdBy,
                    revoked_at = :revokedAt,
                    revoked_by = :revokedBy,
                    status = :status
                WHERE id = :id
                """)
                .param("id", doc.getId())
                .param("tokenHash", doc.getTokenHash())
                .param("storageKey", doc.getStorageKey())
                .param("fileSizeBytes", doc.getFileSizeBytes())
                .param("sha256Hash", doc.getSha256Hash())
                .param("maxDownloads", doc.getMaxDownloads())
                .param("downloadCount", doc.getDownloadCount())
                .param("expiresAt", Timestamp.from(doc.getExpiresAt()))
                .param("createdBy", doc.getCreatedBy())
                .param("revokedAt", doc.getRevokedAt() != null ? Timestamp.from(doc.getRevokedAt()) : null)
                .param("revokedBy", doc.getRevokedBy())
                .param("status", doc.getStatus().persistenceValue())
                .update();

        if (updated == 0) {
            jdbc.sql("""
                    INSERT INTO document
                        (id, token_hash, storage_key, file_size_bytes, sha256_hash,
                         max_downloads, download_count, expires_at, created_at, created_by,
                         revoked_at, revoked_by, status)
                    VALUES
                        (:id, :tokenHash, :storageKey, :fileSizeBytes, :sha256Hash,
                         :maxDownloads, :downloadCount, :expiresAt, :createdAt, :createdBy,
                         :revokedAt, :revokedBy, :status)
                    """)
                    .param("id", doc.getId())
                    .param("tokenHash", doc.getTokenHash())
                    .param("storageKey", doc.getStorageKey())
                    .param("fileSizeBytes", doc.getFileSizeBytes())
                    .param("sha256Hash", doc.getSha256Hash())
                    .param("maxDownloads", doc.getMaxDownloads())
                    .param("downloadCount", doc.getDownloadCount())
                    .param("expiresAt", Timestamp.from(doc.getExpiresAt()))
                    .param("createdAt", Timestamp.from(doc.getCreatedAt()))
                    .param("createdBy", doc.getCreatedBy())
                    .param("revokedAt", doc.getRevokedAt() != null ? Timestamp.from(doc.getRevokedAt()) : null)
                    .param("revokedBy", doc.getRevokedBy())
                    .param("status", doc.getStatus().persistenceValue())
                    .update();
        }
    }

    public void updateStatus(UUID id, DocumentStatus status) {
        jdbc.sql("UPDATE document SET status = :status WHERE id = :id")
                .param("id", id)
                .param("status", status.persistenceValue())
                .update();
    }

    public void deleteById(UUID id) {
        jdbc.sql("DELETE FROM document WHERE id = :id")
                .param("id", id)
                .update();
    }

    /**
     * Atomically claim a download slot with row-level lock.
     * Increments download_count if the document is downloadable.
     * When the claim reaches max_downloads, status is set to EXHAUSTED in the same update.
     *
     * <ul>
     *   <li>{@link DownloadClaimResult.NotFound} — no row for the token hash</li>
     *   <li>{@link DownloadClaimResult.NotAvailable} — exists but not downloadable
     *       (expired, revoked, exhausted, archived) or count race lost</li>
     *   <li>{@link DownloadClaimResult.Claimed} — slot claimed; count already incremented
     *       (and status EXHAUSTED when this was the last allowed download)</li>
     * </ul>
     *
     * Note: Lock is released when the transaction commits.
     */
    public DownloadClaimResult claimDownloadSlot(String tokenHash) {
        Optional<Document> found = jdbc.sql("""
                SELECT * FROM document
                WHERE token_hash = :tokenHash
                FOR UPDATE
                """)
                .param("tokenHash", tokenHash)
                .query(Document::mapRow)
                .optional();

        if (found.isEmpty()) {
            return new DownloadClaimResult.NotFound();
        }

        Document doc = found.get();
        if (!doc.isDownloadable()) {
            return new DownloadClaimResult.NotAvailable();
        }

        // RHS of SET uses pre-update row values: download_count + 1 is the new count.
        int updated = jdbc.sql("""
                UPDATE document
                SET download_count = download_count + 1,
                    status = CASE
                        WHEN download_count + 1 >= max_downloads THEN 'EXHAUSTED'
                        ELSE status
                    END
                WHERE id = :id AND download_count < max_downloads
                """)
                .param("id", doc.getId())
                .update();

        if (updated > 0) {
            int newCount = doc.getDownloadCount() + 1;
            doc.setDownloadCount(newCount);
            if (newCount >= doc.getMaxDownloads()) {
                doc.setStatus(new DocumentStatus.Exhausted());
            }
            return new DownloadClaimResult.Claimed(doc);
        }

        return new DownloadClaimResult.NotAvailable();
    }

    /**
     * Release a previously claimed slot after a failed/incomplete stream.
     * Decrements {@code download_count} and restores {@code ACTIVE} if this claim
     * had moved the row to {@code EXHAUSTED} and a slot is free again.
     * Does not change {@code REVOKED}/{@code ARCHIVED}/{@code CREATING}.
     *
     * @return number of rows updated (0 if already zero count or missing)
     */
    public int releaseDownloadSlot(UUID id) {
        // RHS expressions use pre-update column values.
        return jdbc.sql("""
                UPDATE document
                SET download_count = download_count - 1,
                    status = CASE
                        WHEN status = 'EXHAUSTED' AND download_count - 1 < max_downloads
                            THEN 'ACTIVE'
                        ELSE status
                    END
                WHERE id = :id AND download_count > 0
                """)
                .param("id", id)
                .update();
    }
}
