package com.firstclub.recon.scheduler;

import com.firstclub.platform.scheduler.PrimaryOnlySchedulerGuard;
import com.firstclub.platform.scheduler.lock.SchedulerLockService;
import com.firstclub.recon.service.ReconciliationService;
import com.firstclub.recon.service.SettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Nightly jobs that run after business-day close.
 *
 * <ul>
 *   <li>02:00 — settlement sweep: PG_CLEARING → BANK for yesterday</li>
 *   <li>02:10 — reconciliation report for yesterday</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NightlyReconScheduler {

    private static final String SCHEDULER_NAME_SETTLEMENT    = "nightly-settlement";
    private static final String SCHEDULER_NAME_RECONCILIATION = "nightly-reconciliation";

    private final SettlementService      settlementService;
    private final ReconciliationService  reconciliationService;
    private final SchedulerLockService   schedulerLockService;
    private final PrimaryOnlySchedulerGuard primaryOnlySchedulerGuard;

    /** Run settlement sweep at 02:00 daily for the prior business day. */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void runSettlement() {
        if (!primaryOnlySchedulerGuard.canRunScheduler(SCHEDULER_NAME_SETTLEMENT)) {
            return;
        }
        if (!schedulerLockService.tryAcquireForBatch(SCHEDULER_NAME_SETTLEMENT)) {
            log.debug("[{}] advisory lock not acquired — another node is running this batch", SCHEDULER_NAME_SETTLEMENT);
            return;
        }
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("Nightly settlement starting for {}", yesterday);
        try {
            settlementService.settleForDate(yesterday);
            log.info("Nightly settlement complete for {}", yesterday);
        } catch (Exception ex) {
            log.error("Nightly settlement failed for {}", yesterday, ex);
        }
    }

    /** Run reconciliation at 02:10 daily for the prior business day. */
    @Scheduled(cron = "0 10 2 * * *")
    @Transactional
    public void runReconciliation() {
        if (!primaryOnlySchedulerGuard.canRunScheduler(SCHEDULER_NAME_RECONCILIATION)) {
            return;
        }
        if (!schedulerLockService.tryAcquireForBatch(SCHEDULER_NAME_RECONCILIATION)) {
            log.debug("[{}] advisory lock not acquired — another node is running this batch", SCHEDULER_NAME_RECONCILIATION);
            return;
        }
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("Nightly reconciliation starting for {}", yesterday);
        try {
            reconciliationService.runForDate(yesterday);
            log.info("Nightly reconciliation complete for {}", yesterday);
        } catch (Exception ex) {
            log.error("Nightly reconciliation failed for {}", yesterday, ex);
        }
    }
}
