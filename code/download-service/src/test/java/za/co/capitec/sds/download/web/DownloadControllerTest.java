package za.co.capitec.sds.download.web;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import za.co.capitec.sds.download.service.AuditPublisher;
import za.co.capitec.sds.download.service.ManagementServiceClient;
import za.co.capitec.sds.download.service.ManagementServiceClient.StreamedDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DownloadControllerTest {

    @Mock
    private ManagementServiceClient managementServiceClient;

    @Mock
    private AuditPublisher auditPublisher;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private DownloadController controller;

    @BeforeEach
    void setUp() {
        controller = new DownloadController(managementServiceClient, auditPublisher);
    }

    private void stubRequestMetadata() {
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("10.0.0.9");
        when(request.getHeader("User-Agent")).thenReturn("JUnit");
    }

    @Test
    void download_streamsFileAndPublishesLifecycleEvents() throws Exception {
        String token = "tok123";
        byte[] content = "%PDF-1.4 body".getBytes();
        stubRequestMetadata();
        when(managementServiceClient.stream(token))
                .thenReturn(new StreamedDocument(content.length, "application/pdf", new ByteArrayInputStream(content)));
        ServletOutputStream out = mock(ServletOutputStream.class);
        when(response.getOutputStream()).thenReturn(out);

        controller.download(token, request, response);

        verify(response).setContentType("application/pdf");
        verify(response).setHeader(eq(HttpHeaders.CONTENT_DISPOSITION), any());
        verify(response).setContentLengthLong(content.length);
        verify(out, atLeastOnce()).write(any(byte[].class), eq(0), anyInt());
        verify(auditPublisher).publish(eq(token), eq("DOWNLOAD_REQUEST_RECEIVED"), isNull(), any(), any(), any());
        verify(auditPublisher).publish(eq(token), eq("DOWNLOAD_REQUEST_SUCCESS"), isNull(), any(), any(), any());
    }

    @Test
    void download_skipsContentLengthHeaderWhenUnknown() throws Exception {
        String token = "tok404";
        byte[] content = "data".getBytes();
        stubRequestMetadata();
        when(managementServiceClient.stream(token))
                .thenReturn(new StreamedDocument(-1L, "application/pdf", new ByteArrayInputStream(content)));
        ServletOutputStream out = mock(ServletOutputStream.class);
        when(response.getOutputStream()).thenReturn(out);

        controller.download(token, request, response);

        verify(response, org.mockito.Mockito.never()).setContentLengthLong(anyLong());
    }

    @Test
    void download_propagatesRejectionAndPublishesFailure() throws Exception {
        String token = "gone";
        stubRequestMetadata();
        when(managementServiceClient.stream(token))
                .thenThrow(new ResponseStatusException(HttpStatus.GONE));

        assertThatThrownBy(() -> controller.download(token, request, response))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.GONE);

        verify(auditPublisher).publish(eq(token), eq("DOWNLOAD_REQUEST_RECEIVED"), isNull(), any(), any(), any());
        verify(auditPublisher).publish(eq(token), eq("DOWNLOAD_REQUEST_FAILED"), any(), any(), any(), any());
    }

    @Test
    void download_onStreamInterruptionPublishesDisconnectAnd500() throws Exception {
        String token = "boom";
        byte[] content = "some-bytes".getBytes();
        stubRequestMetadata();
        when(managementServiceClient.stream(token))
                .thenReturn(new StreamedDocument(content.length, "application/pdf", new ByteArrayInputStream(content)));
        ServletOutputStream out = mock(ServletOutputStream.class);
        when(response.getOutputStream()).thenReturn(out);
        doThrow(new IOException("client gone")).when(out).write(any(byte[].class), anyInt(), anyInt());

        assertThatThrownBy(() -> controller.download(token, request, response))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.INTERNAL_SERVER_ERROR);

        verify(auditPublisher).publish(eq(token), eq("DOWNLOAD_REQUEST_FAILED"), eq("CLIENT_DISCONNECT"),
                any(), any(), any());
    }

    @Test
    void download_resolvesClientIpFromForwardedHeader() throws Exception {
        String token = "iptest";
        byte[] content = "x".getBytes();
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50, 10.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("JUnit");
        when(managementServiceClient.stream(token))
                .thenReturn(new StreamedDocument(content.length, "application/pdf", new ByteArrayInputStream(content)));
        ServletOutputStream out = mock(ServletOutputStream.class);
        when(response.getOutputStream()).thenReturn(out);

        controller.download(token, request, response);

        verify(auditPublisher).publish(eq(token), eq("DOWNLOAD_REQUEST_RECEIVED"), isNull(),
                eq("203.0.113.50"), eq("203.0.113.50, 10.0.0.1"), eq("JUnit"));
    }
}
