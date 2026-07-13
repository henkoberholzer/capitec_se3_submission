package za.co.capitec.sds.download.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        ReflectionTestUtils.setField(filter, "requestsPerMinute", 2);
    }

    @Test
    void doFilterInternal_bypassesRateLimitForNonDownloadPaths() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getRequestURI()).thenReturn("/actuator/health");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void doFilterInternal_allowsRequestsUpToLimit() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getRequestURI()).thenReturn("/download/abc123");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("10.1.1.1");

        filter.doFilterInternal(request, response, chain);
        filter.doFilterInternal(request, response, chain);

        verify(chain, times(2)).doFilter(request, response);
        verify(response, never()).setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void doFilterInternal_rejectsWith429WhenLimitExceeded() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        StringWriter body = new StringWriter();
        when(request.getRequestURI()).thenReturn("/download/abc123");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("10.2.2.2");
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        filter.doFilterInternal(request, response, chain);
        filter.doFilterInternal(request, response, chain);
        filter.doFilterInternal(request, response, chain);

        verify(chain, times(2)).doFilter(request, response);
        verify(response).setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(body.toString()).contains("Too many requests");
    }

    @Test
    void doFilterInternal_tracksLimitPerClientIp() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        HttpServletRequest first = mock(HttpServletRequest.class);
        when(first.getRequestURI()).thenReturn("/download/abc123");
        when(first.getHeader("X-Forwarded-For")).thenReturn(null);
        when(first.getRemoteAddr()).thenReturn("10.3.3.3");

        HttpServletRequest second = mock(HttpServletRequest.class);
        when(second.getRequestURI()).thenReturn("/download/abc123");
        when(second.getHeader("X-Forwarded-For")).thenReturn(null);
        when(second.getRemoteAddr()).thenReturn("10.4.4.4");

        filter.doFilterInternal(first, response, chain);
        filter.doFilterInternal(first, response, chain);
        filter.doFilterInternal(second, response, chain);

        verify(chain, times(3)).doFilter(any(), any());
        verify(response, never()).setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    }
}
