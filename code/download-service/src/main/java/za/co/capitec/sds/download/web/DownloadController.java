package za.co.capitec.sds.download.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import za.co.capitec.sds.download.service.AuditPublisher;
import za.co.capitec.sds.download.service.ManagementServiceClient;
import za.co.capitec.sds.download.service.ManagementServiceClient.StreamedDocument;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

@Tag(name = "Download")
@Slf4j
@RestController
@RequiredArgsConstructor
public class DownloadController {

    private static final int BUFFER_SIZE = 32 * 1024;

    private final ManagementServiceClient managementServiceClient;
    private final AuditPublisher auditPublisher;

    @Operation(summary = "Download a document", description = "Streams the document file. Token is single-use within the max-downloads limit.", responses = {
        @ApiResponse(responseCode = "200", description = "PDF file stream"),
        @ApiResponse(responseCode = "404", description = "Token not found"),
        @ApiResponse(responseCode = "410", description = "Document expired, exhausted, or revoked"),
        @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    @GetMapping("/download/{token}")
    public void download(@PathVariable String token, HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        String clientIp = RequestUtils.resolveIp(request);
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String userAgent = request.getHeader("User-Agent");

        auditPublisher.publish(token, "DOWNLOAD_REQUEST_RECEIVED", null, clientIp, forwardedFor, userAgent);

        StreamedDocument doc;
        try {
            doc = managementServiceClient.stream(token);
        } catch (ResponseStatusException ex) {
            auditPublisher.publish(token, "DOWNLOAD_REQUEST_FAILED", ex.getStatusCode().toString(),
                    clientIp, forwardedFor, userAgent);
            throw ex;
        }

        response.setContentType(doc.contentType());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"document.pdf\"");
        if (doc.contentLength() > 0) {
            response.setContentLengthLong(doc.contentLength());
        }

        try (InputStream upstream = doc.body();
             OutputStream out = response.getOutputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = upstream.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            out.flush();
        } catch (IOException e) {
            log.warn("event=DOWNLOAD_INTERRUPTED token={} clientIp={} reason={}", token, clientIp, e.getMessage());
            auditPublisher.publish(token, "DOWNLOAD_REQUEST_FAILED", "CLIENT_DISCONNECT",
                    clientIp, forwardedFor, userAgent);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        auditPublisher.publish(token, "DOWNLOAD_REQUEST_SUCCESS", null, clientIp, forwardedFor, userAgent);
        log.info("event=DOWNLOAD_COMPLETE token={} clientIp={}", token, clientIp);
    }


}
