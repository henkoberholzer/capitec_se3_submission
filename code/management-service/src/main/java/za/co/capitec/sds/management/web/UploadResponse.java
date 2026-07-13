package za.co.capitec.sds.management.web;

import java.util.UUID;

public record UploadResponse(UUID documentId, String downloadUrl) {}
