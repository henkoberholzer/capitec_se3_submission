package za.co.capitec.sds.management.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import za.co.capitec.sds.management.domain.AuditEvent;
import za.co.capitec.sds.management.domain.Document;
import za.co.capitec.sds.management.domain.DocumentStatus;
import za.co.capitec.sds.management.domain.OutboxEvent;
import za.co.capitec.sds.management.repository.AuditEventRepository;
import za.co.capitec.sds.management.repository.DocumentRepository;
import za.co.capitec.sds.management.repository.OutboxEventRepository;
import za.co.capitec.sds.management.exception.DocumentUploadException.FileTooLargeException;
import za.co.capitec.sds.management.exception.DocumentUploadException.InvalidFileTypeException;
import za.co.capitec.sds.management.utils.CountingInputStream;
import za.co.capitec.sds.management.utils.PrefixedInputStream;
import za.co.capitec.sds.management.outbox.handlers.ArchiveDocumentEventHandler;
import za.co.capitec.sds.management.outbox.handlers.DeleteFileEventHandler;
import za.co.capitec.sds.management.outbox.handlers.RemoveArchivedDocumentEventHandler;
import za.co.capitec.sds.management.web.DocumentDetailResponse;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Upload uses a DB-first create protocol to avoid orphan objects, while
 * <strong>streaming</strong> the body to object storage (no full-body buffer):
 * <ol>
 *   <li>PDF magic-byte check (4 bytes only)</li>
 *   <li>TX: insert {@code CREATING} + outbox (placeholder size/hash — not downloadable)</li>
 *   <li>Stream request body → digest + size limit → object store (outside TX)</li>
 *   <li>TX: write final size/hash, promote {@code ACTIVE}, UPLOAD audit</li>
 * </ol>
 * SHA-256 is for ACTIVE correlation / audit archive — it is computed <em>while</em>
 * streaming, not before. Failure after step 2 leaves {@code CREATING} for outbox cleanup.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private static final byte[] PDF_MAGIC = {0x25, 0x50, 0x44, 0x46};

    /** Placeholder until stream completes; CREATING rows are never archived or downloadable. */
    private static final String PENDING_SHA256 = "";
    private static final long PENDING_SIZE = 0L;

    private final DocumentRepository documentRepository;
    private final AuditEventRepository auditEventRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final StorageService storageService;
    private final TokenService tokenService;
    private final PlatformTransactionManager transactionManager;

    @Value("${sds.upload.max-file-size-mb}")
    private int maxFileSizeMb;

    public UploadResult upload(
            InputStream bodyStream,
            int maxDownloads,
            Instant expiresAt,
            String callerId,
            long contentLengthHint) throws IOException {

        long maxBytes = (long) maxFileSizeMb * 1024 * 1024;

        if (contentLengthHint > 0 && contentLengthHint > maxBytes) {
            throw new FileTooLargeException("File exceeds maximum size of " + maxFileSizeMb + " MB");
        }

        // Only peek at magic — remainder of the body is streamed to storage.
        byte[] magic = bodyStream.readNBytes(4);
        if (magic.length < 4 || !isPdfMagic(magic)) {
            throw new InvalidFileTypeException("File is not a valid PDF (magic bytes check failed)");
        }

        UUID documentId = UUID.randomUUID();
        String storageKey = "documents/" + documentId + "/payload";

        // 1) Metadata + cleanup outbox first (no object bytes required yet).
        String rawToken = insertCreatingDocument(
                documentId, storageKey, maxDownloads, expiresAt, callerId);

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }

        InputStream combined = new PrefixedInputStream(magic, bodyStream);
        DigestInputStream digestStream = new DigestInputStream(combined, digest);
        CountingInputStream countingStream = new CountingInputStream(digestStream, maxBytes);

        // Known Content-Length still applies to the full body (magic + rest).
        long storeLength = contentLengthHint > 0 ? contentLengthHint : -1;

        // 2) Single-pass stream to object storage while hashing / enforcing size.
        try {
            storageService.store(storageKey, countingStream, storeLength);
        } catch (CountingInputStream.SizeLimitExceededException ex) {
            log.error(
                    "event=UPLOAD_STORE_FAILED documentId={} reason=SIZE_LIMIT — leaving CREATING for outbox cleanup",
                    documentId);
            throw new FileTooLargeException("File exceeds maximum size of " + maxFileSizeMb + " MB");
        } catch (IOException | RuntimeException ex) {
            if (isSizeLimit(ex)) {
                log.error(
                        "event=UPLOAD_STORE_FAILED documentId={} reason=SIZE_LIMIT — leaving CREATING for outbox cleanup",
                        documentId);
                throw new FileTooLargeException("File exceeds maximum size of " + maxFileSizeMb + " MB");
            }
            log.error(
                    "event=UPLOAD_STORE_FAILED documentId={} storageKey={} — leaving CREATING for outbox cleanup",
                    documentId,
                    storageKey,
                    ex);
            throw ex;
        }

        long fileSizeBytes = countingStream.getBytesRead();
        String sha256Hash = HexFormat.of().formatHex(digest.digest());

        // 3) Finalize only after a successful put — client never gets a link for CREATING.
        try {
            promoteToActive(documentId, callerId, fileSizeBytes, sha256Hash);
        } catch (RuntimeException ex) {
            log.error(
                    "event=UPLOAD_PROMOTE_FAILED documentId={} — object may exist; CREATING + outbox will clean up",
                    documentId,
                    ex);
            throw ex;
        }

        log.info("event=UPLOAD documentId={} callerId={} fileSizeBytes={}", documentId, callerId, fileSizeBytes);
        return new UploadResult(documentId, rawToken, expiresAt, maxDownloads);
    }

    private static boolean isSizeLimit(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof CountingInputStream.SizeLimitExceededException) {
                return true;
            }
        }
        return false;
    }

    private String insertCreatingDocument(
            UUID documentId,
            String storageKey,
            int maxDownloads,
            Instant expiresAt,
            String callerId) {

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> {
            String rawToken;
            String tokenHash;
            do {
                rawToken = tokenService.generateToken();
                tokenHash = tokenService.hashToken(rawToken);
            } while (documentRepository.findByTokenHash(tokenHash).isPresent());

            Document doc = new Document();
            doc.setId(documentId);
            doc.setTokenHash(tokenHash);
            doc.setStorageKey(storageKey);
            doc.setFileSizeBytes(PENDING_SIZE);
            doc.setSha256Hash(PENDING_SHA256);
            doc.setMaxDownloads(maxDownloads);
            doc.setExpiresAt(expiresAt);
            doc.setCreatedBy(callerId);
            doc.setStatus(new DocumentStatus.Creating());
            documentRepository.save(doc);

            // Per-feature max attempts (idempotent delete/remove can retry more; archive involves Kafka).
            outboxEventRepository.save(OutboxEvent.create(
                    documentId, expiresAt.plus(1, ChronoUnit.MINUTES),
                    DeleteFileEventHandler.EVENT_TYPE, 8));
            outboxEventRepository.save(OutboxEvent.create(
                    documentId, expiresAt.plus(2, ChronoUnit.MINUTES),
                    ArchiveDocumentEventHandler.EVENT_TYPE, 8));
            outboxEventRepository.save(OutboxEvent.create(
                    documentId, expiresAt.plus(5, ChronoUnit.MINUTES),
                    RemoveArchivedDocumentEventHandler.EVENT_TYPE, 5));

            log.info("event=UPLOAD_CREATING documentId={} callerId={}", documentId, callerId);
            return rawToken;
        });
    }

    private void promoteToActive(UUID documentId, String callerId, long fileSizeBytes, String sha256Hash) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            Document doc = documentRepository.findById(documentId)
                    .orElseThrow(() -> new IllegalStateException("Document missing after create: " + documentId));

            if (!(doc.getStatus() instanceof DocumentStatus.Creating)) {
                throw new IllegalStateException(
                        "Expected CREATING when promoting document " + documentId
                                + " was " + doc.getStatus().persistenceValue());
            }

            doc.setFileSizeBytes(fileSizeBytes);
            doc.setSha256Hash(sha256Hash);
            doc.setStatus(new DocumentStatus.Active());
            documentRepository.save(doc);
            auditEventRepository.save(AuditEvent.success(documentId, "UPLOAD", callerId));
        });
    }

    @Transactional
    public void revoke(UUID documentId, String callerId, boolean isAdmin) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (!isAdmin && !doc.getCreatedBy().equals(callerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        if (doc.getStatus() instanceof DocumentStatus.Revoked
                || doc.getStatus() instanceof DocumentStatus.Archived
                || doc.getStatus() instanceof DocumentStatus.Creating) {
            return;
        }

        doc.setStatus(new DocumentStatus.Revoked());
        doc.setRevokedAt(Instant.now());
        doc.setRevokedBy(callerId);
        documentRepository.save(doc);

        auditEventRepository.save(AuditEvent.success(documentId, "REVOKE", callerId));
        log.info("event=REVOKE documentId={} callerId={}", documentId, callerId);
    }

    @Transactional(readOnly = true)
    public DocumentDetailResponse getDocumentDetail(UUID documentId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var auditEvents = auditEventRepository.findByDocumentIdOrderByOccurredAtAsc(documentId);
        return DocumentDetailResponse.from(doc, auditEvents);
    }

    private boolean isPdfMagic(byte[] bytes) {
        return bytes[0] == PDF_MAGIC[0]
                && bytes[1] == PDF_MAGIC[1]
                && bytes[2] == PDF_MAGIC[2]
                && bytes[3] == PDF_MAGIC[3];
    }
}
