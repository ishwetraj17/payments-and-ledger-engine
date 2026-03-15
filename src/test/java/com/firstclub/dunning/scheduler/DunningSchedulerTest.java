package com.firstclub.dunning.scheduler;

import com.firstclub.dunning.service.DunningService;
import com.firstclub.platform.scheduler.PrimaryOnlySchedulerGuard;
import com.firstclub.platform.scheduler.lock.SchedulerLockService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DunningScheduler} multi-instance safety.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DunningScheduler — Guard conditions")
class DunningSchedulerTest {

    @Mock private DunningService dunningService;
    @Mock private SchedulerLockService schedulerLockService;
    @Mock private PrimaryOnlySchedulerGuard primaryOnlySchedulerGuard;

    @InjectMocks private DunningScheduler scheduler;

    @Nested
    @DisplayName("Guard conditions")
    class GuardConditions {

        @Test
        @DisplayName("runDunning_notPrimary_skipsExecution")
        void runDunning_notPrimary_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(false);

            scheduler.runDunning();

            verify(schedulerLockService, never()).tryAcquireForBatch(any());
            verify(dunningService, never()).processDueAttempts();
        }

        @Test
        @DisplayName("runDunning_lockNotAcquired_skipsExecution")
        void runDunning_lockNotAcquired_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(false);

            scheduler.runDunning();

            verify(dunningService, never()).processDueAttempts();
        }

        @Test
        @DisplayName("runDunning_primaryAndLockAcquired_invokesService")
        void runDunning_happyPath_invokesService() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(true);

            scheduler.runDunning();

            verify(dunningService).processDueAttempts();
        }
    }
}
