package com.firstclub.dunning.scheduler;

import com.firstclub.dunning.service.DunningServiceV2;
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
 * Unit tests for {@link DunningSchedulerV2} multi-instance safety.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DunningSchedulerV2 — Guard conditions")
class DunningSchedulerV2Test {

    @Mock private DunningServiceV2 dunningServiceV2;
    @Mock private SchedulerLockService schedulerLockService;
    @Mock private PrimaryOnlySchedulerGuard primaryOnlySchedulerGuard;

    @InjectMocks private DunningSchedulerV2 scheduler;

    @Nested
    @DisplayName("Guard conditions")
    class GuardConditions {

        @Test
        @DisplayName("runDunningV2_notPrimary_skipsExecution")
        void runDunningV2_notPrimary_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(false);

            scheduler.runDunningV2();

            verify(schedulerLockService, never()).tryAcquireForBatch(any());
            verify(dunningServiceV2, never()).processDueV2Attempts();
        }

        @Test
        @DisplayName("runDunningV2_lockNotAcquired_skipsExecution")
        void runDunningV2_lockNotAcquired_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(false);

            scheduler.runDunningV2();

            verify(dunningServiceV2, never()).processDueV2Attempts();
        }

        @Test
        @DisplayName("runDunningV2_primaryAndLockAcquired_invokesService")
        void runDunningV2_happyPath_invokesService() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(true);

            scheduler.runDunningV2();

            verify(dunningServiceV2).processDueV2Attempts();
        }
    }
}
