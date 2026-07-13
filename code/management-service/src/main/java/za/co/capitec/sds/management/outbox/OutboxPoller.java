package za.co.capitec.sds.management.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private final OutboxProcessor outboxProcessor;

    @Value("${sds.outbox.batch-size}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${sds.outbox.poll-interval-seconds}000")
    public void poll() {
        outboxProcessor.pollAndProcess(batchSize);
    }
}
