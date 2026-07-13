package za.co.capitec.sds.management.repository;

import za.co.capitec.sds.management.domain.Document;

/**
 * Outcome of attempting to claim a download slot for a token hash.
 */
public sealed interface DownloadClaimResult {

    /** No document exists for the given token. */
    record NotFound() implements DownloadClaimResult {}

    /** Document exists but is expired, exhausted, revoked, or otherwise not downloadable. */
    record NotAvailable() implements DownloadClaimResult {}

    /** Slot claimed; download count already incremented. */
    record Claimed(Document document) implements DownloadClaimResult {}
}
