package za.co.capitec.sds.download.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${sds.kafka.topics.download-events}")
    private String downloadEventsTopic;

    public void publish(String token, String event, String description, String clientIp,
            String forwardedFor, String userAgent) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", event);
            payload.put("occurredAt", Instant.now().toString());
            if (description != null) {
                payload.put("description", description);
            }
            if (clientIp != null) {
                payload.put("clientIp", clientIp);
            }
            if (forwardedFor != null) {
                payload.put("forwardedFor", forwardedFor);
            }
            if (userAgent != null) {
                payload.put("userAgent", userAgent);
            }
            kafkaTemplate.send(downloadEventsTopic, token, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("event=DOWNLOAD_EVENT_PUBLISH_FAILED event={}", event, e);
        }
    }
}
