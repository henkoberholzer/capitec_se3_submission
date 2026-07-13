package za.co.capitec.sds.management.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import za.co.capitec.sds.management.domain.AuditEvent;
import za.co.capitec.sds.management.domain.Document;
import za.co.capitec.sds.management.repository.AuditEventRepository;
import za.co.capitec.sds.management.repository.DocumentRepository;
import za.co.capitec.sds.management.repository.DownloadClaimResult;

import java.io.InputStream;
import java.util.UUID;

/**
 * Use-case service for the internal download path.
 * <p>
 * Slot accounting: {@link #claimDownload} reserves a slot (concurrent-safe), the controller
 * streams bytes, then either {@link #recordDownloadComplete} (success) or
 * {@link #releaseDownloadSlot} (stream failure / client disconnect) so partial transfers
 * do not permanently consume max_downloads.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InternalDownloadService {

    private final DocumentRepository documentRepository;
    private final AuditEventRepository auditEventRepository;
    private final TokenService tokenService;
    private final StorageService storageService;

    /**
     * Hash the raw token, lock the document row, and claim one download slot.
     * When the claim reaches max downloads, status is set to EXHAUSTED (see repository).
     *
     * @throws ResponseStatusException 404 if token unknown, 410 if not downloadable
     */
    @Transactional
    public Document claimDownload(String rawToken) {
        String tokenHash = tokenService.hashToken(rawToken);
        DownloadClaimResult claimResult = documentRepository.claimDownloadSlot(tokenHash);

        if (claimResult instanceof DownloadClaimResult.NotFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (claimResult instanceof DownloadClaimResult.NotAvailable) {
            throw new ResponseStatusException(HttpStatus.GONE);
        }
        if (!(claimResult instanceof DownloadClaimResult.Claimed claimed)) {
            throw new IllegalStateException("Unexpected claim result: " + claimResult);
        }
        return claimed.document();
    }

    /**
     * Open the object-store stream for a previously claimed document.
     * Not transactional — avoids holding a DB connection during I/O setup.
     */
    public InputStream openContentStream(Document document) {
        log.info("event=STREAM_START documentId={}", document.getId());
        return storageService.stream(document.getStorageKey());
    }

    /**
     * Record successful full stream. Call only after all bytes were written to the client.
     */
    @Transactional
    public void recordDownloadComplete(UUID documentId, String callerId) {
        auditEventRepository.save(AuditEvent.success(documentId, "DOWNLOAD_COMPLETE", callerId));
        log.info("event=STREAM_COMPLETE documentId={}", documentId);
    }

    /**
     * Undo {@link #claimDownload} after an incomplete stream (I/O error, client disconnect,
     * storage failure). Restores a download opportunity when possible.
     */
    @Transactional
    public void releaseDownloadSlot(UUID documentId) {
        int updated = documentRepository.releaseDownloadSlot(documentId);
        if (updated > 0) {
            log.info("event=DOWNLOAD_SLOT_RELEASED documentId={}", documentId);
        } else {
            log.warn("event=DOWNLOAD_SLOT_RELEASE_NOOP documentId={}", documentId);
        }
    }
}
