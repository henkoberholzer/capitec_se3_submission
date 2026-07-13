package za.co.capitec.sds.management.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.capitec.sds.management.service.DocumentService;
import za.co.capitec.sds.management.service.UploadResult;
import za.co.capitec.sds.management.exception.DocumentUploadException.FileTooLargeException;
import za.co.capitec.sds.management.exception.DocumentUploadException.InvalidFileTypeException;
import org.springframework.beans.factory.annotation.Value;
import za.co.capitec.sds.management.utils.JwtUtils;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Documents")
@Slf4j
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    @Value("${sds.download.base-url}")
    private String downloadBaseUrl;

    private final DocumentService documentService;

    @Operation(summary = "Upload a document", responses = {
        @ApiResponse(responseCode = "201", description = "Uploaded"),
        @ApiResponse(responseCode = "413", description = "File too large"),
        @ApiResponse(responseCode = "415", description = "Not a PDF")
    })
    @PreAuthorize("hasAuthority('SCOPE_securedownload:create')")
    @PostMapping(consumes = "application/pdf")
    public ResponseEntity<?> upload(
            HttpServletRequest request,
            @RequestHeader("x-capitec-max-downloads") int maxDownloads,
            @RequestHeader(value = "x-capitec-expires-in", required = false) Long expiresInSeconds,
            @RequestHeader(value = "x-capitec-expires-at", required = false) String expiresAtIso,
            @AuthenticationPrincipal Jwt jwt) {

        if (maxDownloads < 1) {
            return ResponseEntity.badRequest().body(Map.of("error", "x-capitec-max-downloads must be >= 1"));
        }

        Instant expiresAt;
        try {
            expiresAt = resolveExpiry(expiresInSeconds, expiresAtIso);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid x-capitec-expires-at format, expected ISO-8601"));
        }
        if (expiresAt == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Exactly one of x-capitec-expires-in or x-capitec-expires-at is required"));
        }
        if (!expiresAt.isAfter(Instant.now())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Expiry must be in the future"));
        }

        String callerId = JwtUtils.resolveCallerId(jwt);
        long contentLength = request.getContentLengthLong();

        try {
            UploadResult result = documentService.upload(
                    request.getInputStream(), maxDownloads, expiresAt, callerId, contentLength);

            String downloadUrl = buildDownloadUrl(result.token());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new UploadResponse(result.documentId(), downloadUrl));
        } catch (FileTooLargeException e) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", e.getMessage()));
        } catch (InvalidFileTypeException e) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            log.error("Upload IO error callerId={}", callerId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Upload failed"));
        }
    }

    @Operation(summary = "Revoke a document", responses = {
        @ApiResponse(responseCode = "204", description = "Revoked"),
        @ApiResponse(responseCode = "403", description = "Not owner"),
        @ApiResponse(responseCode = "404", description = "Not found")
    })
    @PreAuthorize("hasAuthority('SCOPE_securedownload:revoke')")
    @DeleteMapping("/{documentId}")
    public ResponseEntity<?> revoke(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal Jwt jwt) {

        String callerId = JwtUtils.resolveCallerId(jwt);
        boolean isAdmin = JwtUtils.hasScope(jwt, "documents:admin");
        documentService.revoke(documentId, callerId, isAdmin);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get document detail", responses = {
        @ApiResponse(responseCode = "200", description = "Document detail"),
        @ApiResponse(responseCode = "404", description = "Not found")
    })
    @PreAuthorize("hasAuthority('SCOPE_securedownload:getinfo')")
    @GetMapping("/{documentId}")
    public ResponseEntity<?> getDocument(@PathVariable UUID documentId) {
        return ResponseEntity.ok(documentService.getDocumentDetail(documentId));
    }

    private Instant resolveExpiry(Long expiresInSeconds, String expiresAtIso) {
        if (expiresInSeconds != null && expiresAtIso != null) {
            return null;
        }
        if (expiresInSeconds != null) {
            return Instant.now().plusSeconds(expiresInSeconds);
        }
        if (expiresAtIso != null) {
            return Instant.parse(expiresAtIso);
        }
        return null;
    }

    private String buildDownloadUrl(String token) {
        return downloadBaseUrl + "/download/" + token;
    }
}
