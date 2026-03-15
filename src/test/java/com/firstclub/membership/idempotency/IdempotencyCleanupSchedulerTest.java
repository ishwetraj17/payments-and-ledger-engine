package com.firstclub.membership.idempotency;

import com.firstclub.platform.idempotency.scheduler.IdempotencyCleanupScheduler;
import com.firstclub.platform.idempotency.service.IdempotencyRecordService;
import com.firstclub.platform.scheduler.PrimaryOnlySchedulerGuard;
import com.firstclub.platform.scheduler.lock.SchedulerLockService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IdempotencyCleanupScheduler}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyCleanupScheduler")
class IdempotencyCleanupSchedulerTest {

    @Mock private IdempotencyRecordService recordService;
    @Mock private SchedulerLockService schedulerLockService;
    @Mock private PrimaryOnlySchedulerGuard primaryOnlySchedulerGuard;
    @InjectMocks private IdempotencyCleanupScheduler scheduler;

    @Test
    @DisplayName("resetStuckProcessing delegates to recordService and returns count")
    void resetStuck_delegatesToRecordService() {
        when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
        when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(true);
        when(recordService.resetStuckProcessing(any())).thenReturn(5);

        int count = scheduler.resetStuckProcessing();

        assertThat(count).isEqualTo(5);
        verify(recordService).resetStuckProcessing(any());
    }

    @Test
    @DisplayName("resetStuckProcessing uses configured threshold (minutes)")
    void resetStuck_usesConfiguredThreshold() {
        // Set threshold to 10 minutes via reflection (simulating @Value injection)
        ReflectionTestUtils.setField(scheduler, "stuckThresholdMinutes", 10);
        when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
        when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(true);
        when(recordService.resetStuckProcessing(any())).thenReturn(0);

        scheduler.resetStuckProcessing();

        verify(recordService).resetStuckProcessing(
                argThat((Duration d) -> d.toMinutes() == 10));
    }

    @Test
    @DisplayName("Returns 0 when no stuck records found")
    void resetStuck_returnsZeroWhenNothing() {
        when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
        when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(true);
        when(recordService.resetStuckProcessing(any())).thenReturn(0);

        int count = scheduler.resetStuckProcessing();

        assertThat(count).isZero();
    }

}
