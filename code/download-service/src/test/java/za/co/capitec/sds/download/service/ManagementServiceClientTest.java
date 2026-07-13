package za.co.capitec.sds.download.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import za.co.capitec.sds.download.service.ManagementServiceClient.StreamedDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagementServiceClientTest {

    private HttpClient http;
    private TokenProvider tokenProvider;
    private ManagementServiceClient client;

    @BeforeEach
    void setUp() {
        http = mock(HttpClient.class);
        tokenProvider = mock(TokenProvider.class);
        client = new ManagementServiceClient(tokenProvider);
        ReflectionTestUtils.setField(client, "http", http);
        ReflectionTestUtils.setField(client, "baseUrl", "http://management:8080");
        when(tokenProvider.getAccessToken()).thenReturn("service-token");
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<InputStream> mockResponse() {
        return mock(HttpResponse.class);
    }

    @Test
    void stream_returnsDocumentOn200WithHeaders() throws Exception {
        byte[] bytes = "%PDF-1.7".getBytes();
        HttpResponse<InputStream> response = mockResponse();
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(
                Map.of("Content-Length", List.of(String.valueOf(bytes.length)),
                        "Content-Type", List.of("application/pdf")),
                (a, b) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(bytes));
        when(http.<InputStream>send(any(), any())).thenReturn(response);

        StreamedDocument doc = client.stream("tok");

        assertThat(doc.contentLength()).isEqualTo(bytes.length);
        assertThat(doc.contentType()).isEqualTo("application/pdf");
        assertThat(doc.body().readAllBytes()).isEqualTo(bytes);
    }

    @Test
    void stream_defaultsContentTypeAndLengthWhenHeadersMissing() throws Exception {
        HttpResponse<InputStream> response = mockResponse();
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(http.<InputStream>send(any(), any())).thenReturn(response);

        StreamedDocument doc = client.stream("tok");

        assertThat(doc.contentLength()).isEqualTo(-1L);
        assertThat(doc.contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void stream_throws404AndClosesBody() throws Exception {
        HttpResponse<InputStream> response = mockResponse();
        InputStream body = mock(InputStream.class);
        when(response.statusCode()).thenReturn(404);
        when(response.body()).thenReturn(body);
        when(http.<InputStream>send(any(), any())).thenReturn(response);

        assertThatThrownBy(() -> client.stream("tok"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
        verify(body).close();
    }

    @Test
    void stream_throws410OnGone() throws Exception {
        HttpResponse<InputStream> response = mockResponse();
        when(response.statusCode()).thenReturn(410);
        when(response.body()).thenReturn(mock(InputStream.class));
        when(http.<InputStream>send(any(), any())).thenReturn(response);

        assertThatThrownBy(() -> client.stream("tok"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.GONE);
    }

    @Test
    void stream_throwsBadGatewayOnUnexpectedStatus() throws Exception {
        HttpResponse<InputStream> response = mockResponse();
        when(response.statusCode()).thenReturn(500);
        when(response.body()).thenReturn(mock(InputStream.class));
        when(http.<InputStream>send(any(), any())).thenReturn(response);

        assertThatThrownBy(() -> client.stream("tok"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_GATEWAY);
    }

    @Test
    void stream_throwsServiceUnavailableOnIoError() throws Exception {
        when(http.<InputStream>send(any(), any())).thenThrow(new IOException("connection refused"));

        assertThatThrownBy(() -> client.stream("tok"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
