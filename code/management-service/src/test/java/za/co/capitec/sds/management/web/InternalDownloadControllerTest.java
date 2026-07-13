package za.co.capitec.sds.management.web;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import za.co.capitec.sds.management.domain.Document;
import za.co.capitec.sds.management.domain.DocumentStatus;
import za.co.capitec.sds.management.service.InternalDownloadService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalDownloadControllerTest {

    @Mock
    private InternalDownloadService internalDownloadService;

    @Mock
    private HttpServletResponse response;

    @Mock
    private Jwt jwt;

    private InternalDownloadController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalDownloadController(internalDownloadService);
    }

    @Test
    void stream_withInvalidToken_throws404() throws IOException {
        String token = "invalid_token";

        when(internalDownloadService.claimDownload(token))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> controller.stream(token, response, jwt))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);

        verify(internalDownloadService, never()).openContentStream(any());
        verify(internalDownloadService, never()).recordDownloadComplete(any(), any());
        verify(internalDownloadService, never()).releaseDownloadSlot(any());
    }

    @Test
    void stream_withUnavailableDocument_throws410() throws IOException {
        String token = "valid_token";

        when(internalDownloadService.claimDownload(token))
                .thenThrow(new ResponseStatusException(HttpStatus.GONE));

        assertThatThrownBy(() -> controller.stream(token, response, jwt))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.GONE);

        verify(internalDownloadService, never()).openContentStream(any());
        verify(internalDownloadService, never()).recordDownloadComplete(any(), any());
        verify(internalDownloadService, never()).releaseDownloadSlot(any());
    }

    @Test
    void stream_withValidDocument_streamsAndRecordsComplete() throws IOException {
        String token = "valid_token";
        UUID docId = UUID.randomUUID();
        byte[] fileContent = "PDF content here".getBytes();

        Document doc = new Document();
        doc.setId(docId);
        doc.setStorageKey("documents/" + docId + "/payload");
        doc.setStatus(new DocumentStatus.Active());
        doc.setExpiresAt(Instant.now().plusSeconds(3600));
        doc.setMaxDownloads(5);
        doc.setDownloadCount(1);
        doc.setFileSizeBytes(fileContent.length);

        InputStream fileStream = new ByteArrayInputStream(fileContent);
        ServletOutputStream responseStream = mock(ServletOutputStream.class);

        when(jwt.getSubject()).thenReturn("service-client");
        when(internalDownloadService.claimDownload(token)).thenReturn(doc);
        when(response.getOutputStream()).thenReturn(responseStream);
        when(internalDownloadService.openContentStream(doc)).thenReturn(fileStream);

        controller.stream(token, response, jwt);

        verify(response).setContentType("application/pdf");
        verify(response).setContentLengthLong(fileContent.length);
        verify(internalDownloadService).openContentStream(doc);
        verify(internalDownloadService).recordDownloadComplete(eq(docId), any());
        verify(internalDownloadService, never()).releaseDownloadSlot(any());
    }

    @Test
    void stream_whenOpenStreamFails_releasesSlotAndDoesNotRecordComplete() throws IOException {
        String token = "valid_token";
        UUID docId = UUID.randomUUID();

        Document doc = new Document();
        doc.setId(docId);
        doc.setStorageKey("documents/" + docId + "/payload");
        doc.setFileSizeBytes(1024);

        when(jwt.getSubject()).thenReturn("service-client");
        when(internalDownloadService.claimDownload(token)).thenReturn(doc);
        when(internalDownloadService.openContentStream(doc))
                .thenThrow(new RuntimeException("storage unavailable"));

        assertThatThrownBy(() -> controller.stream(token, response, jwt))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("storage unavailable");

        verify(internalDownloadService).releaseDownloadSlot(docId);
        verify(internalDownloadService, never()).recordDownloadComplete(any(), any());
    }

    @Test
    void stream_whenClientWriteFails_releasesSlot() throws IOException {
        String token = "valid_token";
        UUID docId = UUID.randomUUID();
        byte[] fileContent = "PDF content here".getBytes();

        Document doc = new Document();
        doc.setId(docId);
        doc.setStorageKey("documents/" + docId + "/payload");
        doc.setFileSizeBytes(fileContent.length);

        InputStream fileStream = new ByteArrayInputStream(fileContent);
        ServletOutputStream responseStream = mock(ServletOutputStream.class);

        when(jwt.getSubject()).thenReturn("service-client");
        when(internalDownloadService.claimDownload(token)).thenReturn(doc);
        when(response.getOutputStream()).thenReturn(responseStream);
        when(internalDownloadService.openContentStream(doc)).thenReturn(fileStream);
        org.mockito.Mockito.doThrow(new IOException("broken pipe"))
                .when(responseStream).write(any(byte[].class), any(int.class), any(int.class));

        assertThatThrownBy(() -> controller.stream(token, response, jwt))
            .isInstanceOf(IOException.class);

        verify(internalDownloadService).releaseDownloadSlot(docId);
        verify(internalDownloadService, never()).recordDownloadComplete(any(), any());
    }
}
