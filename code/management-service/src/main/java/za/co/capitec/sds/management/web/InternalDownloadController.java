package za.co.capitec.sds.management.web;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.capitec.sds.management.domain.Document;
import za.co.capitec.sds.management.service.InternalDownloadService;
import za.co.capitec.sds.management.utils.JwtUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalDownloadController {

    private static final int BUFFER_SIZE = 32 * 1024;

    private final InternalDownloadService internalDownloadService;

    @GetMapping("/stream/{token}")
    public void stream(@PathVariable("token") String token, HttpServletResponse response,
            @AuthenticationPrincipal Jwt jwt) throws IOException {
        String callerId = JwtUtils.resolveCallerId(jwt);

        // Reserve slot first (concurrent-safe). Refunded below if the stream does not complete.
        Document doc = internalDownloadService.claimDownload(token);

        response.setContentType("application/pdf");
        response.setContentLengthLong(doc.getFileSizeBytes());

        boolean streamCompleted = false;
        try {
            try (InputStream s3Stream = internalDownloadService.openContentStream(doc);
                 OutputStream out = response.getOutputStream()) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int n;
                while ((n = s3Stream.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
            }
            streamCompleted = true;
        } finally {
            if (!streamCompleted) {
                try {
                    internalDownloadService.releaseDownloadSlot(doc.getId());
                } catch (Exception releaseEx) {
                    log.error("event=DOWNLOAD_SLOT_RELEASE_FAILED documentId={}", doc.getId(), releaseEx);
                }
            }
        }

        if (streamCompleted) {
            internalDownloadService.recordDownloadComplete(doc.getId(), callerId);
        }
    }
}
