package za.co.capitec.sds.download.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleResponseStatus_returnsHtmlFor410() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.GONE, "link expired");

        ResponseEntity<?> result = handler.handleResponseStatus(ex);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
        assertThat(result.getBody()).isInstanceOf(String.class);
        String body = (String) result.getBody();
        assertThat(body).contains("Download link no longer available");
        assertThat(body).contains("Error 410");
    }

    @Test
    void handleResponseStatus_returnsHtmlFor404() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND);

        ResponseEntity<?> result = handler.handleResponseStatus(ex);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
        assertThat(result.getBody()).isInstanceOf(String.class);
        String body = (String) result.getBody();
        assertThat(body).contains("Download link not found");
        assertThat(body).contains("Error 404");
    }

    @Test
    void handleResponseStatus_returnsJsonForOtherStatuses() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "upstream down");

        ResponseEntity<?> result = handler.handleResponseStatus(ex);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(result.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) result.getBody();
        assertThat(body).containsEntry("error", "upstream down");
    }

    @Test
    void handleNoResource_returns404HtmlPage() {
        NoResourceFoundException ex = mock(NoResourceFoundException.class);

        ResponseEntity<String> result = handler.handleNoResource(ex);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
        assertThat(result.getBody()).contains("Download link not found");
    }

    @Test
    void handleUnexpected_returns500InternalError() {
        ResponseEntity<Map<String, Object>> result = handler.handleUnexpected(new RuntimeException("boom"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result.getBody()).containsEntry("error", "Internal server error");
    }
}
