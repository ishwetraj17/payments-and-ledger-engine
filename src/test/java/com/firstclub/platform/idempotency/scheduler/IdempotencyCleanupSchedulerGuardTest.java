package com.firstclub.platform.idempotency.scheduler;

import com.firstclub.platform.idempotency.service.IdempotencyRecordService;
import com.firstclub.platform.scheduler.PrimaryOnlySchedulerGuard;
import com.firstclub.platform.scheduler.lock.SchedulerLockService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IdempotencyCleanupScheduler} multi-instance safety.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyCleanupScheduler — Guard conditions")
class IdempotencyCleanupSchedulerGuardTest {

    @Mock private IdempotencyRecordService recordService;
    @Mock private SchedulerLockService schedulerLockService;
    @Mock private PrimaryOnlySchedulerGuard primaryOnlySchedulerGuard;

    @InjectMocks private IdempotencyCleanupScheduler scheduler;

    @Nested
    @DisplayName("Guard conditions")
    class GuardConditions {

        @Test
        @DisplayName("resetStuckProcessing_notPrimary_skipsAndReturnsZero")
        void resetStuck_notPrimary_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(false);

            int result = scheduler.resetStuckProcessing();

            assertThat(result).isZero();
            verify(schedulerLockService, never()).tryAcquireForBatch(any());
            verify(recordService, never()).resetStuckProcessing(any());
        }

        @Test
        @DisplayName("resetStuckProcessing_lockNotAcquired_skipsAndReturnsZero")
        void resetStuck_lockNotAcquired_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(false);

            int result = scheduler.resetStuckProcessing();

            assertThat(result).isZero();
            verify(recordService, never()).resetStuckProcessing(any());
        }

        @Test
        @DisplayName("resetStuckProcessing_primaryAndLockAcquired_delegatesToRecordService")
        void resetStuck_happyPath_delegatesToService() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(true);
            when(recordService.resetStuckProcessing(any())).thenReturn(3);

            int result = scheduler.resetStuckProcessing();

            assertThat(result).isEqualTo(3);
            verify(recordService).resetStuckProcessing(any(Duration.class));
        }
    }
}
