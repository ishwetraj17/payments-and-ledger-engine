package com.firstclub.dunning.scheduler;

import com.firstclub.dunning.service.RenewalService;
import com.firstclub.membership.entity.Subscription;
import com.firstclub.platform.scheduler.PrimaryOnlySchedulerGuard;
import com.firstclub.platform.scheduler.lock.SchedulerLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Scheduled job that drives subscription auto-renewals.
 *
 * <p>Runs every 5 minutes.  For each subscription whose {@code next_renewal_at}
 * has elapsed and whose status is {@code ACTIVE}, it creates a renewal invoice
 * and attempts payment via {@link com.firstclub.dunning.port.PaymentGatewayPort}.
 * Failed charges result in {@code PAST_DUE} status and a dunning schedule.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RenewalScheduler {

    private static final String SCHEDULER_NAME = "subscription-renewal";

    private final RenewalService renewalService;
    private final SchedulerLockService schedulerLockService;
    private final PrimaryOnlySchedulerGuard primaryOnlySchedulerGuard;

    /**
     * Fixed-rate: every 5 minutes.
     * Initial delay of 60 s lets the application context fully warm up before
     * the first run.
     */
    @Scheduled(fixedRate = 300_000, initialDelay = 60_000)
    @Transactional
    public void runRenewals() {
        if (!primaryOnlySchedulerGuard.canRunScheduler(SCHEDULER_NAME)) {
            return;
        }
        if (!schedulerLockService.tryAcquireForBatch(SCHEDULER_NAME)) {
            log.debug("[{}] advisory lock not acquired — another node is running this batch", SCHEDULER_NAME);
            return;
        }
        List<Subscription> due = renewalService.findDueForRenewal();
        if (due.isEmpty()) {
            return;
        }
        log.info("Renewal scheduler: {} subscription(s) due", due.size());
        for (Subscription sub : due) {
            try {
                renewalService.processRenewal(sub.getId());
            } catch (Exception e) {
                log.error("Renewal failed for sub {}: {}", sub.getId(), e.getMessage(), e);
            }
        }
    }
}
