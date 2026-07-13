package za.co.capitec.sds.management.service;

import java.time.Instant;
import java.util.UUID;

public record UploadResult(UUID documentId, String token, Instant expiresAt, int maxDownloads) {}
