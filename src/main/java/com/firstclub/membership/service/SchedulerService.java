package com.firstclub.membership.service;

import com.firstclub.platform.scheduler.PrimaryOnlySchedulerGuard;
import com.firstclub.platform.scheduler.lock.SchedulerLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled background jobs for subscription lifecycle management.
 *
 * Cron expressions are externalized to application.properties so they can be
 * adjusted per environment without rebuilding (membership.scheduler.*).
 *
 * Both jobs run nightly by default (01:00 and 01:05) to minimize impact on
 * traffic while keeping subscription states accurate.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SchedulerService {

    private static final String SCHEDULER_NAME_EXPIRE  = "membership-expire-subscriptions";
    private static final String SCHEDULER_NAME_RENEWAL = "membership-process-renewals";

    private final MembershipService membershipService;
    private final SchedulerLockService schedulerLockService;
    private final PrimaryOnlySchedulerGuard primaryOnlySchedulerGuard;

    /**
     * Marks subscriptions whose endDate has passed as EXPIRED.
     * Runs nightly at 01:00 by default.
     */
    @Scheduled(cron = "${membership.scheduler.expire-subscriptions:0 0 1 * * *}")
    @Transactional
    public void processExpiredSubscriptions() {
        if (!primaryOnlySchedulerGuard.canRunScheduler(SCHEDULER_NAME_EXPIRE)) {
            return;
        }
        if (!schedulerLockService.tryAcquireForBatch(SCHEDULER_NAME_EXPIRE)) {
            log.debug("[{}] advisory lock not acquired — another node is running this batch", SCHEDULER_NAME_EXPIRE);
            return;
        }
        log.info("[Scheduler] Starting nightly expired-subscription sweep...");
        try {
            membershipService.processExpiredSubscriptions();
            log.info("[Scheduler] Expired-subscription sweep completed.");
        } catch (Exception e) {
            log.error("[Scheduler] Error during expired-subscription sweep", e);
        }
    }

    /**
     * Auto-renews subscriptions whose nextBillingDate is due.
     * Runs nightly at 01:05 by default (after expiry sweep).
     */
    @Scheduled(cron = "${membership.scheduler.process-renewals:0 5 1 * * *}")
    @Transactional
    public void processRenewals() {
        if (!primaryOnlySchedulerGuard.canRunScheduler(SCHEDULER_NAME_RENEWAL)) {
            return;
        }
        if (!schedulerLockService.tryAcquireForBatch(SCHEDULER_NAME_RENEWAL)) {
            log.debug("[{}] advisory lock not acquired — another node is running this batch", SCHEDULER_NAME_RENEWAL);
            return;
        }
        log.info("[Scheduler] Starting nightly renewal sweep...");
        try {
            membershipService.processRenewals();
            log.info("[Scheduler] Renewal sweep completed.");
        } catch (Exception e) {
            log.error("[Scheduler] Error during renewal sweep", e);
        }
    }
}
