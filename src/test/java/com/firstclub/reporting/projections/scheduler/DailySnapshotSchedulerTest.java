package com.firstclub.reporting.projections.scheduler;

import com.firstclub.platform.scheduler.PrimaryOnlySchedulerGuard;
import com.firstclub.platform.scheduler.lock.SchedulerLockService;
import com.firstclub.reporting.projections.service.LedgerSnapshotService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DailySnapshotScheduler} multi-instance safety.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DailySnapshotScheduler — Guard conditions")
class DailySnapshotSchedulerTest {

    @Mock private LedgerSnapshotService ledgerSnapshotService;
    @Mock private SchedulerLockService schedulerLockService;
    @Mock private PrimaryOnlySchedulerGuard primaryOnlySchedulerGuard;

    @InjectMocks private DailySnapshotScheduler scheduler;

    @Nested
    @DisplayName("Guard conditions")
    class GuardConditions {

        @Test
        @DisplayName("runDailySnapshot_notPrimary_skipsExecution")
        void runDailySnapshot_notPrimary_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(false);

            scheduler.runDailySnapshot();

            verify(schedulerLockService, never()).tryAcquireForBatch(any());
            verify(ledgerSnapshotService, never()).generateSnapshotForDate(any());
        }

        @Test
        @DisplayName("runDailySnapshot_lockNotAcquired_skipsExecution")
        void runDailySnapshot_lockNotAcquired_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(false);

            scheduler.runDailySnapshot();

            verify(ledgerSnapshotService, never()).generateSnapshotForDate(any());
        }

        @Test
        @DisplayName("runDailySnapshot_primaryAndLockAcquired_invokesService")
        void runDailySnapshot_happyPath_invokesService() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(true);
            when(ledgerSnapshotService.generateSnapshotForDate(any())).thenReturn(List.of());

            scheduler.runDailySnapshot();

            verify(ledgerSnapshotService).generateSnapshotForDate(any(LocalDate.class));
        }
    }
}
