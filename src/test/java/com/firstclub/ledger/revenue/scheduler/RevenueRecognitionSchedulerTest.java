package com.firstclub.ledger.revenue.scheduler;

import com.firstclub.ledger.revenue.dto.RevenueRecognitionRunResponseDTO;
import com.firstclub.ledger.revenue.service.RevenueRecognitionPostingService;
import com.firstclub.platform.scheduler.PrimaryOnlySchedulerGuard;
import com.firstclub.platform.scheduler.lock.SchedulerLockService;
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
 * Unit tests for {@link RevenueRecognitionScheduler} multi-instance safety.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RevenueRecognitionScheduler — Guard conditions")
class RevenueRecognitionSchedulerTest {

    @Mock private RevenueRecognitionPostingService postingService;
    @Mock private SchedulerLockService schedulerLockService;
    @Mock private PrimaryOnlySchedulerGuard primaryOnlySchedulerGuard;

    @InjectMocks private RevenueRecognitionScheduler scheduler;

    @Nested
    @DisplayName("Guard conditions")
    class GuardConditions {

        @Test
        @DisplayName("runDailyRecognition_notPrimary_skipsExecution")
        void runDailyRecognition_notPrimary_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(false);

            scheduler.runDailyRecognition();

            verify(schedulerLockService, never()).tryAcquireForBatch(any());
            verify(postingService, never()).postDueRecognitionsForDate(any());
        }

        @Test
        @DisplayName("runDailyRecognition_lockNotAcquired_skipsExecution")
        void runDailyRecognition_lockNotAcquired_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(false);

            scheduler.runDailyRecognition();

            verify(postingService, never()).postDueRecognitionsForDate(any());
        }

        @Test
        @DisplayName("runDailyRecognition_primaryAndLockAcquired_invokesService")
        void runDailyRecognition_happyPath_invokesService() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(true);
            when(postingService.postDueRecognitionsForDate(any())).thenReturn(
                    RevenueRecognitionRunResponseDTO.builder()
                            .scheduled(1).posted(1).failed(0).failedScheduleIds(List.of()).build());

            scheduler.runDailyRecognition();

            verify(postingService).postDueRecognitionsForDate(any(LocalDate.class));
        }
    }
}
