package za.co.capitec.sds.download.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TokenProviderTest {

    private HttpClient http;
    private TokenProvider tokenProvider;

    @BeforeEach
    void setUp() {
        http = mock(HttpClient.class);
        tokenProvider = new TokenProvider();
        ReflectionTestUtils.setField(tokenProvider, "http", http);
        ReflectionTestUtils.setField(tokenProvider, "tokenUrl", "http://keycloak/token");
        ReflectionTestUtils.setField(tokenProvider, "clientId", "sds-download-client");
        ReflectionTestUtils.setField(tokenProvider, "clientSecret", "secret");
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> mockResponse() {
        return mock(HttpResponse.class);
    }

    @Test
    void getAccessToken_returnsCachedTokenWhileValid() {
        ReflectionTestUtils.setField(tokenProvider, "cachedToken", "cached-token");
        ReflectionTestUtils.setField(tokenProvider, "tokenExpiry", Instant.now().plusSeconds(3600));

        assertThat(tokenProvider.getAccessToken()).isEqualTo("cached-token");
        verifyNoInteractions(http);
    }

    @Test
    void getAccessToken_fetchesFreshTokenWhenCacheEmpty() throws Exception {
        HttpResponse<String> response = mockResponse();
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"access_token\":\"fresh-token\",\"expires_in\":300}");
        when(http.<String>send(any(), any())).thenReturn(response);

        assertThat(tokenProvider.getAccessToken()).isEqualTo("fresh-token");
    }

    @Test
    void getAccessToken_throwsWhenTokenEndpointRejects() throws Exception {
        HttpResponse<String> response = mockResponse();
        when(response.statusCode()).thenReturn(401);
        when(http.<String>send(any(), any())).thenReturn(response);

        assertThatThrownBy(() -> tokenProvider.getAccessToken())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getAccessToken_throwsOnIoError() throws Exception {
        when(http.<String>send(any(), any())).thenThrow(new IOException("keycloak unreachable"));

        assertThatThrownBy(() -> tokenProvider.getAccessToken())
                .isInstanceOf(IllegalStateException.class);
    }
}
