package com.firstclub.dunning.scheduler;

import com.firstclub.dunning.service.RenewalService;
import com.firstclub.membership.entity.Subscription;
import com.firstclub.platform.scheduler.PrimaryOnlySchedulerGuard;
import com.firstclub.platform.scheduler.lock.SchedulerLockService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RenewalScheduler} multi-instance safety.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RenewalScheduler — Guard conditions")
class RenewalSchedulerTest {

    @Mock private RenewalService renewalService;
    @Mock private SchedulerLockService schedulerLockService;
    @Mock private PrimaryOnlySchedulerGuard primaryOnlySchedulerGuard;

    @InjectMocks private RenewalScheduler scheduler;

    @Nested
    @DisplayName("Guard conditions")
    class GuardConditions {

        @Test
        @DisplayName("runRenewals_notPrimary_skipsExecution")
        void runRenewals_notPrimary_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(false);

            scheduler.runRenewals();

            verify(schedulerLockService, never()).tryAcquireForBatch(any());
            verify(renewalService, never()).findDueForRenewal();
        }

        @Test
        @DisplayName("runRenewals_lockNotAcquired_skipsExecution")
        void runRenewals_lockNotAcquired_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(false);

            scheduler.runRenewals();

            verify(renewalService, never()).findDueForRenewal();
        }

        @Test
        @DisplayName("runRenewals_primaryAndLockAcquired_processesEachSubscription")
        void runRenewals_happyPath_processesSubscriptions() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(true);
            Subscription sub = new Subscription();
            setField(sub, "id", 1L);
            when(renewalService.findDueForRenewal()).thenReturn(List.of(sub));

            scheduler.runRenewals();

            verify(renewalService).processRenewal(1L);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
