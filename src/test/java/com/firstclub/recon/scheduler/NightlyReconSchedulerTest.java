package com.firstclub.recon.scheduler;

import com.firstclub.platform.scheduler.PrimaryOnlySchedulerGuard;
import com.firstclub.platform.scheduler.lock.SchedulerLockService;
import com.firstclub.recon.service.ReconciliationService;
import com.firstclub.recon.service.SettlementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NightlyReconScheduler} multi-instance safety.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NightlyReconScheduler — Guard conditions")
class NightlyReconSchedulerTest {

    @Mock private SettlementService settlementService;
    @Mock private ReconciliationService reconciliationService;
    @Mock private SchedulerLockService schedulerLockService;
    @Mock private PrimaryOnlySchedulerGuard primaryOnlySchedulerGuard;

    @InjectMocks private NightlyReconScheduler scheduler;

    @Nested
    @DisplayName("runSettlement guard conditions")
    class SettlementGuards {

        @Test
        @DisplayName("runSettlement_notPrimary_skipsExecution")
        void runSettlement_notPrimary_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(false);

            scheduler.runSettlement();

            verify(schedulerLockService, never()).tryAcquireForBatch(any());
            verify(settlementService, never()).settleForDate(any());
        }

        @Test
        @DisplayName("runSettlement_lockNotAcquired_skipsExecution")
        void runSettlement_lockNotAcquired_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(false);

            scheduler.runSettlement();

            verify(settlementService, never()).settleForDate(any());
        }

        @Test
        @DisplayName("runSettlement_primaryAndLockAcquired_invokesService")
        void runSettlement_happyPath_invokesService() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(true);

            scheduler.runSettlement();

            verify(settlementService).settleForDate(any(LocalDate.class));
        }
    }

    @Nested
    @DisplayName("runReconciliation guard conditions")
    class ReconciliationGuards {

        @Test
        @DisplayName("runReconciliation_notPrimary_skipsExecution")
        void runReconciliation_notPrimary_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(false);

            scheduler.runReconciliation();

            verify(schedulerLockService, never()).tryAcquireForBatch(any());
            verify(reconciliationService, never()).runForDate(any());
        }

        @Test
        @DisplayName("runReconciliation_lockNotAcquired_skipsExecution")
        void runReconciliation_lockNotAcquired_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(false);

            scheduler.runReconciliation();

            verify(reconciliationService, never()).runForDate(any());
        }

        @Test
        @DisplayName("runReconciliation_primaryAndLockAcquired_invokesService")
        void runReconciliation_happyPath_invokesService() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(true);

            scheduler.runReconciliation();

            verify(reconciliationService).runForDate(any(LocalDate.class));
        }
    }
}
