package za.co.capitec.sds.download.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Slf4j
@Service
public class ManagementServiceClient {

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${sds.management-service.base-url}")
    private String baseUrl;

    private final TokenProvider tokenProvider;

    public ManagementServiceClient(TokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    public StreamedDocument stream(String token) {
        String accessToken = tokenProvider.getAccessToken();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/internal/stream/" + token))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("event=STREAM_ERROR token={}", token, e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Management service unavailable");
        }

        if (response.statusCode() == 404) {
            closeQuietly(response.body());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (response.statusCode() == 410) {
            closeQuietly(response.body());
            throw new ResponseStatusException(HttpStatus.GONE);
        }
        if (response.statusCode() != 200) {
            closeQuietly(response.body());
            log.error("event=STREAM_UNEXPECTED_STATUS status={} token={}", response.statusCode(), token);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        }

        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        String contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream");

        return new StreamedDocument(contentLength, contentType, response.body());
    }

    private void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException e) {
            log.debug("Failed to close stream", e);
        }
    }

    public record StreamedDocument(long contentLength, String contentType, InputStream body) {}
}
