package com.firstclub.ledger.revenue.scheduler;

import com.firstclub.ledger.revenue.dto.RevenueRecognitionRunResponseDTO;
import com.firstclub.ledger.revenue.service.RevenueRecognitionPostingService;
import com.firstclub.platform.scheduler.PrimaryOnlySchedulerGuard;
import com.firstclub.platform.scheduler.lock.SchedulerLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Runs the daily revenue recognition posting job.
 *
 * <p>Disabled by default; enable via
 * {@code revenue.recognition.scheduler.enabled=true} in the environment.
 * The cron expression can be overridden with
 * {@code revenue.recognition.scheduler.cron} (default: 01:00 every day).
 */
@Component
@ConditionalOnProperty(
        name  = "revenue.recognition.scheduler.enabled",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class RevenueRecognitionScheduler {

    private static final String SCHEDULER_NAME = "revenue-recognition";

    private final RevenueRecognitionPostingService postingService;
    private final SchedulerLockService schedulerLockService;
    private final PrimaryOnlySchedulerGuard primaryOnlySchedulerGuard;

    @Scheduled(cron = "${revenue.recognition.scheduler.cron:0 0 1 * * *}")
    @Transactional
    public void runDailyRecognition() {
        if (!primaryOnlySchedulerGuard.canRunScheduler(SCHEDULER_NAME)) {
            return;
        }
        if (!schedulerLockService.tryAcquireForBatch(SCHEDULER_NAME)) {
            log.debug("[{}] advisory lock not acquired — another node is running this batch", SCHEDULER_NAME);
            return;
        }
        LocalDate today = LocalDate.now();
        log.info("Scheduled revenue recognition job starting for date {}", today);
        try {
            RevenueRecognitionRunResponseDTO result =
                    postingService.postDueRecognitionsForDate(today);
            log.info("Scheduled revenue recognition complete for {}: scheduled={}, posted={}, failed={}",
                    today, result.getScheduled(), result.getPosted(), result.getFailed());
            if (!result.getFailedScheduleIds().isEmpty()) {
                log.warn("Failed schedule IDs: {}", result.getFailedScheduleIds());
            }
        } catch (Exception e) {
            log.error("Scheduled revenue recognition job failed for date {}: {}", today, e.getMessage(), e);
        }
    }
}
