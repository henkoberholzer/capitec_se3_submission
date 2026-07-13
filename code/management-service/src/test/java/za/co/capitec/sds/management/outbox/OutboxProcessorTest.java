package za.co.capitec.sds.management.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import za.co.capitec.sds.management.domain.OutboxEvent;
import za.co.capitec.sds.management.repository.OutboxEventRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxProcessorTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private OutboxEventHandler handler1;

    @Mock
    private OutboxEventHandler handler2;

    @Mock
    private PlatformTransactionManager transactionManager;

    private OutboxProcessor outboxProcessor;

    @BeforeEach
    void setUp() {
        when(handler1.eventType()).thenReturn("TYPE_A");
        when(handler2.eventType()).thenReturn("TYPE_B");
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        lenient().doNothing().when(transactionManager).commit(any());
        lenient().doNothing().when(transactionManager).rollback(any());

        outboxProcessor = new OutboxProcessor(
                outboxEventRepository, List.of(handler1, handler2), transactionManager);
    }

    @Test
    void pollAndProcess_withEmptyBatch_returnsEarly() {
        when(outboxEventRepository.claimBatch(any(Instant.class), any(Duration.class), eq(10)))
                .thenReturn(List.of());

        outboxProcessor.pollAndProcess(10);

        verify(outboxEventRepository, never()).delete(any(Long.class));
        verify(outboxEventRepository, never()).markFailed(any(Long.class));
        verify(outboxEventRepository, never()).scheduleRetry(any(Long.class), any(Instant.class));
    }

    @Test
    void pollAndProcess_withSuccessfulEvent_deletesAfterHandling() {
        OutboxEvent event = claimedEvent(1L, "TYPE_A", 1, 5);
        when(outboxEventRepository.claimBatch(any(Instant.class), any(Duration.class), eq(10)))
                .thenReturn(List.of(event));

        outboxProcessor.pollAndProcess(10);

        verify(handler1).handle(event);
        verify(outboxEventRepository).delete(event.getId());
    }

    @Test
    void pollAndProcess_withFailedHandler_underMaxAttempts_schedulesRetry() {
        OutboxEvent event = claimedEvent(1L, "TYPE_A", 1, 5);
        when(outboxEventRepository.claimBatch(any(Instant.class), any(Duration.class), eq(10)))
                .thenReturn(List.of(event));
        doThrow(new RuntimeException("Handler failed")).when(handler1).handle(event);

        outboxProcessor.pollAndProcess(10);

        verify(outboxEventRepository, never()).delete(event.getId());
        verify(outboxEventRepository, never()).markFailed(event.getId());
        verify(outboxEventRepository).scheduleRetry(eq(event.getId()), any(Instant.class));
    }

    @Test
    void pollAndProcess_withFailedHandler_atMaxAttempts_marksFailed() {
        OutboxEvent event = claimedEvent(1L, "TYPE_A", 5, 5);
        when(outboxEventRepository.claimBatch(any(Instant.class), any(Duration.class), eq(10)))
                .thenReturn(List.of(event));
        doThrow(new RuntimeException("Handler failed")).when(handler1).handle(event);

        outboxProcessor.pollAndProcess(10);

        verify(outboxEventRepository).markFailed(event.getId());
        verify(outboxEventRepository, never()).scheduleRetry(any(Long.class), any(Instant.class));
    }

    @Test
    void pollAndProcess_withUnregisteredHandler_marksFailedWhenAttemptsExhausted() {
        OutboxEvent event = claimedEvent(1L, "UNKNOWN_TYPE", 3, 3);
        when(outboxEventRepository.claimBatch(any(Instant.class), any(Duration.class), eq(10)))
                .thenReturn(List.of(event));

        outboxProcessor.pollAndProcess(10);

        verify(outboxEventRepository).markFailed(event.getId());
    }

    @Test
    void pollAndProcess_withMixedResults_handlesEachIndependently() {
        OutboxEvent event1 = claimedEvent(1L, "TYPE_A", 1, 5);
        OutboxEvent event2 = claimedEvent(2L, "TYPE_B", 1, 5);

        when(outboxEventRepository.claimBatch(any(Instant.class), any(Duration.class), eq(10)))
                .thenReturn(List.of(event1, event2));
        doThrow(new RuntimeException("Failed")).when(handler1).handle(event1);

        outboxProcessor.pollAndProcess(10);

        verify(outboxEventRepository).scheduleRetry(eq(event1.getId()), any(Instant.class));
        verify(handler2).handle(event2);
        verify(outboxEventRepository).delete(event2.getId());
    }

    @Test
    void backoffDelay_growsExponentiallyThenCaps() {
        assertThat(OutboxProcessor.backoffDelay(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(OutboxProcessor.backoffDelay(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(OutboxProcessor.backoffDelay(3)).isEqualTo(Duration.ofSeconds(20));
        assertThat(OutboxProcessor.backoffDelay(10)).isEqualTo(Duration.ofMinutes(5));
    }

    private static OutboxEvent claimedEvent(long id, String type, int attemptCount, int maxAttempts) {
        OutboxEvent event = OutboxEvent.create(UUID.randomUUID(), Instant.now(), type, maxAttempts);
        event.setId(id);
        event.setAttemptCount(attemptCount);
        return event;
    }
}
