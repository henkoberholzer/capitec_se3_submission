package za.co.capitec.sds.download.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditPublisherTest {

    private static final String TOPIC = "document-download-event";

    private KafkaTemplate<String, String> kafkaTemplate;
    private ObjectMapper objectMapper;
    private AuditPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        objectMapper = new ObjectMapper();
        publisher = new AuditPublisher(kafkaTemplate, objectMapper);
        ReflectionTestUtils.setField(publisher, "downloadEventsTopic", TOPIC);
    }

    @Test
    void publish_sendsEventKeyedByTokenWithAllFields() throws Exception {
        publisher.publish("tok1", "DOWNLOAD_REQUEST_SUCCESS", "ok", "10.0.0.1", "10.0.0.1, 10.0.0.2", "curl/8");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TOPIC), eq("tok1"), payload.capture());

        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.get("event").asText()).isEqualTo("DOWNLOAD_REQUEST_SUCCESS");
        assertThat(json.get("description").asText()).isEqualTo("ok");
        assertThat(json.get("clientIp").asText()).isEqualTo("10.0.0.1");
        assertThat(json.get("forwardedFor").asText()).isEqualTo("10.0.0.1, 10.0.0.2");
        assertThat(json.get("userAgent").asText()).isEqualTo("curl/8");
        assertThat(json.hasNonNull("occurredAt")).isTrue();
    }

    @Test
    void publish_omitsNullOptionalFields() throws Exception {
        publisher.publish("tok2", "DOWNLOAD_REQUEST_RECEIVED", null, null, null, null);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TOPIC), eq("tok2"), payload.capture());

        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.get("event").asText()).isEqualTo("DOWNLOAD_REQUEST_RECEIVED");
        assertThat(json.has("description")).isFalse();
        assertThat(json.has("clientIp")).isFalse();
        assertThat(json.has("forwardedFor")).isFalse();
        assertThat(json.has("userAgent")).isFalse();
    }

    @Test
    void publish_swallowsPublishFailure() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("broker down"));

        assertThatCode(() ->
                publisher.publish("tok3", "DOWNLOAD_REQUEST_SUCCESS", null, "10.0.0.1", null, null))
                .doesNotThrowAnyException();
    }
}
