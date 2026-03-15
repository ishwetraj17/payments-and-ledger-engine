package com.firstclub.membership.service;

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
 * Unit tests for {@link SchedulerService} multi-instance safety.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SchedulerService — Guard conditions")
class SchedulerServiceGuardTest {

    @Mock private MembershipService membershipService;
    @Mock private SchedulerLockService schedulerLockService;
    @Mock private PrimaryOnlySchedulerGuard primaryOnlySchedulerGuard;

    @InjectMocks private SchedulerService schedulerService;

    @Nested
    @DisplayName("processExpiredSubscriptions guard conditions")
    class ExpireGuards {

        @Test
        @DisplayName("processExpiredSubscriptions_notPrimary_skipsExecution")
        void processExpired_notPrimary_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(false);

            schedulerService.processExpiredSubscriptions();

            verify(schedulerLockService, never()).tryAcquireForBatch(any());
            verify(membershipService, never()).processExpiredSubscriptions();
        }

        @Test
        @DisplayName("processExpiredSubscriptions_lockNotAcquired_skipsExecution")
        void processExpired_lockNotAcquired_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(false);

            schedulerService.processExpiredSubscriptions();

            verify(membershipService, never()).processExpiredSubscriptions();
        }

        @Test
        @DisplayName("processExpiredSubscriptions_primaryAndLockAcquired_invokesService")
        void processExpired_happyPath_invokesService() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(true);

            schedulerService.processExpiredSubscriptions();

            verify(membershipService).processExpiredSubscriptions();
        }
    }

    @Nested
    @DisplayName("processRenewals guard conditions")
    class RenewalGuards {

        @Test
        @DisplayName("processRenewals_notPrimary_skipsExecution")
        void processRenewals_notPrimary_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(false);

            schedulerService.processRenewals();

            verify(schedulerLockService, never()).tryAcquireForBatch(any());
            verify(membershipService, never()).processRenewals();
        }

        @Test
        @DisplayName("processRenewals_lockNotAcquired_skipsExecution")
        void processRenewals_lockNotAcquired_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(false);

            schedulerService.processRenewals();

            verify(membershipService, never()).processRenewals();
        }

        @Test
        @DisplayName("processRenewals_primaryAndLockAcquired_invokesService")
        void processRenewals_happyPath_invokesService() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(true);

            schedulerService.processRenewals();

            verify(membershipService).processRenewals();
        }
    }
}
