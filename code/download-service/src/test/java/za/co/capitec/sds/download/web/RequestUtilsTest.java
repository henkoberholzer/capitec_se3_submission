package za.co.capitec.sds.download.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestUtilsTest {

    @Test
    void resolveIp_prefersFirstXForwardedForEntry() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.7, 10.0.0.1, 10.0.0.2");

        assertThat(RequestUtils.resolveIp(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void resolveIp_trimsWhitespaceFromForwardedEntry() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("  198.51.100.4  ");

        assertThat(RequestUtils.resolveIp(request)).isEqualTo("198.51.100.4");
    }

    @Test
    void resolveIp_fallsBackToRemoteAddrWhenHeaderMissing() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("192.0.2.10");

        assertThat(RequestUtils.resolveIp(request)).isEqualTo("192.0.2.10");
    }

    @Test
    void resolveIp_fallsBackToRemoteAddrWhenHeaderBlank() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("   ");
        when(request.getRemoteAddr()).thenReturn("192.0.2.11");

        assertThat(RequestUtils.resolveIp(request)).isEqualTo("192.0.2.11");
    }
}
