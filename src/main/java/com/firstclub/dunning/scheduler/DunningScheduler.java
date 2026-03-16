package com.firstclub.dunning.scheduler;

import com.firstclub.dunning.service.DunningService;
import com.firstclub.platform.scheduler.PrimaryOnlySchedulerGuard;
import com.firstclub.platform.scheduler.lock.SchedulerLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled job that processes due dunning attempts.
 *
 * <p>Runs every 10 minutes.  For each {@code SCHEDULED} attempt whose
 * {@code scheduled_at} has elapsed it re-attempts the payment.  A successful
 * retry reactivates the subscription; a final failure suspends it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DunningScheduler {

    private static final String SCHEDULER_NAME = "dunning-v1";

    private final DunningService dunningService;
    private final SchedulerLockService schedulerLockService;
    private final PrimaryOnlySchedulerGuard primaryOnlySchedulerGuard;

    /** Every 10 minutes; initial delay 90 s. */
    @Scheduled(fixedRate = 600_000, initialDelay = 90_000)
    @Transactional
    public void runDunning() {
        if (!primaryOnlySchedulerGuard.canRunScheduler(SCHEDULER_NAME)) {
            return;
        }
        if (!schedulerLockService.tryAcquireForBatch(SCHEDULER_NAME)) {
            log.debug("[{}] advisory lock not acquired — another node is running this batch", SCHEDULER_NAME);
            return;
        }
        log.debug("Dunning scheduler: checking for due attempts");
        dunningService.processDueAttempts();
    }
}
