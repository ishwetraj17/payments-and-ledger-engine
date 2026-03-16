package com.firstclub.dunning.scheduler;

import com.firstclub.dunning.service.DunningServiceV2;
import com.firstclub.platform.scheduler.PrimaryOnlySchedulerGuard;
import com.firstclub.platform.scheduler.lock.SchedulerLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled job that processes due v2 policy-driven dunning attempts.
 *
 * <p>Runs every 5 minutes (offset by 15 s from the v1 {@link DunningScheduler})
 * to avoid database lock contention when both schedulers fire simultaneously.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DunningSchedulerV2 {

    private static final String SCHEDULER_NAME = "dunning-v2";

    private final DunningServiceV2 dunningServiceV2;
    private final SchedulerLockService schedulerLockService;
    private final PrimaryOnlySchedulerGuard primaryOnlySchedulerGuard;

    /** Every 5 minutes; initial delay 105 s. */
    @Scheduled(fixedRate = 300_000, initialDelay = 105_000)
    @Transactional
    public void runDunningV2() {
        if (!primaryOnlySchedulerGuard.canRunScheduler(SCHEDULER_NAME)) {
            return;
        }
        if (!schedulerLockService.tryAcquireForBatch(SCHEDULER_NAME)) {
            log.debug("[{}] advisory lock not acquired — another node is running this batch", SCHEDULER_NAME);
            return;
        }
        log.info("[{}] Scheduler tick — processing due policy-driven attempts", SCHEDULER_NAME);
        dunningServiceV2.processDueV2Attempts();
    }
}
