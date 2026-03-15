package com.firstclub.risk.scheduler;

import com.firstclub.platform.scheduler.PrimaryOnlySchedulerGuard;
import com.firstclub.platform.scheduler.lock.SchedulerLockService;
import com.firstclub.risk.entity.RiskEvent;
import com.firstclub.risk.repository.RiskEventRepository;
import com.firstclub.risk.review.ManualReviewEscalationService;
import com.firstclub.risk.scoring.RiskScoreDecayService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RiskMaintenanceScheduler} multi-instance safety.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RiskMaintenanceScheduler — Guard conditions")
class RiskMaintenanceSchedulerTest {

    @Mock private ManualReviewEscalationService escalationService;
    @Mock private RiskScoreDecayService decayService;
    @Mock private RiskEventRepository riskEventRepository;
    @Mock private SchedulerLockService schedulerLockService;
    @Mock private PrimaryOnlySchedulerGuard primaryOnlySchedulerGuard;

    @InjectMocks private RiskMaintenanceScheduler scheduler;

    @Nested
    @DisplayName("escalateOverdueReviewCases guard conditions")
    class EscalationGuards {

        @Test
        @DisplayName("escalateOverdueReviewCases_notPrimary_skipsExecution")
        void escalate_notPrimary_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(false);

            scheduler.escalateOverdueReviewCases();

            verify(schedulerLockService, never()).tryAcquireForBatch(any());
            verify(escalationService, never()).escalateOverdueCases();
        }

        @Test
        @DisplayName("escalateOverdueReviewCases_lockNotAcquired_skipsExecution")
        void escalate_lockNotAcquired_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(false);

            scheduler.escalateOverdueReviewCases();

            verify(escalationService, never()).escalateOverdueCases();
        }

        @Test
        @DisplayName("escalateOverdueReviewCases_primaryAndLockAcquired_invokesService")
        void escalate_happyPath_invokesService() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(true);
            when(escalationService.escalateOverdueCases()).thenReturn(0);

            scheduler.escalateOverdueReviewCases();

            verify(escalationService).escalateOverdueCases();
        }
    }

    @Nested
    @DisplayName("refreshDecayedScores guard conditions")
    class DecayGuards {

        @Test
        @DisplayName("refreshDecayedScores_notPrimary_skipsExecution")
        void decay_notPrimary_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(false);

            scheduler.refreshDecayedScores();

            verify(schedulerLockService, never()).tryAcquireForBatch(any());
            verify(riskEventRepository, never()).findByBaseScoreIsNotNull();
        }

        @Test
        @DisplayName("refreshDecayedScores_lockNotAcquired_skipsExecution")
        void decay_lockNotAcquired_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(false);

            scheduler.refreshDecayedScores();

            verify(riskEventRepository, never()).findByBaseScoreIsNotNull();
        }

        @Test
        @DisplayName("refreshDecayedScores_primaryAndLockAcquired_invokesDecayService")
        void decay_happyPath_invokesService() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(true);
            RiskEvent event = new RiskEvent();
            when(riskEventRepository.findByBaseScoreIsNotNull()).thenReturn(List.of(event));

            scheduler.refreshDecayedScores();

            verify(decayService).decayAll(List.of(event));
        }
    }
}
