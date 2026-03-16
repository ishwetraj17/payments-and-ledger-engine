package com.firstclub.risk.scheduler;

import com.firstclub.platform.scheduler.PrimaryOnlySchedulerGuard;
import com.firstclub.platform.scheduler.lock.SchedulerLockService;
import com.firstclub.risk.repository.RiskEventRepository;
import com.firstclub.risk.review.ManualReviewEscalationService;
import com.firstclub.risk.scoring.RiskScoreDecayService;
import com.firstclub.risk.entity.RiskEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Scheduled maintenance jobs for the risk sub-system.
 *
 * <ul>
 *   <li>Every 30 minutes: escalate manual-review cases that have breached their SLA deadline.</li>
 *   <li>Daily at 02:00: refresh {@code decayed_score} on all scored risk events.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RiskMaintenanceScheduler {

    private static final String SCHEDULER_NAME_ESCALATION = "risk-escalation";
    private static final String SCHEDULER_NAME_DECAY      = "risk-score-decay";

    private final ManualReviewEscalationService escalationService;
    private final RiskScoreDecayService         decayService;
    private final RiskEventRepository           riskEventRepository;
    private final SchedulerLockService          schedulerLockService;
    private final PrimaryOnlySchedulerGuard     primaryOnlySchedulerGuard;

    /**
     * Auto-escalates any manual-review cases whose {@code slaDueAt} has passed and
     * whose status is still {@code OPEN}. Runs every 30 minutes so SLA breaches are
     * caught within half an hour of expiry.
     */
    @Scheduled(fixedRate = 1_800_000)
    @Transactional
    public void escalateOverdueReviewCases() {
        if (!primaryOnlySchedulerGuard.canRunScheduler(SCHEDULER_NAME_ESCALATION)) {
            return;
        }
        if (!schedulerLockService.tryAcquireForBatch(SCHEDULER_NAME_ESCALATION)) {
            log.debug("[{}] advisory lock not acquired — another node is running this batch", SCHEDULER_NAME_ESCALATION);
            return;
        }
        int escalated = escalationService.escalateOverdueCases();
        if (escalated > 0) {
            log.info("[RiskMaintenance] Escalated {} SLA-overdue manual-review case(s)", escalated);
        }
    }

    /**
     * Refreshes the {@code decayed_score} column for every risk event that carries a
     * {@code baseScore}. Runs nightly at 02:00 so score decay reflects the
     * age of each event without requiring on-the-fly recalculation on every read.
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void refreshDecayedScores() {
        if (!primaryOnlySchedulerGuard.canRunScheduler(SCHEDULER_NAME_DECAY)) {
            return;
        }
        if (!schedulerLockService.tryAcquireForBatch(SCHEDULER_NAME_DECAY)) {
            log.debug("[{}] advisory lock not acquired — another node is running this batch", SCHEDULER_NAME_DECAY);
            return;
        }
        List<RiskEvent> events = riskEventRepository.findByBaseScoreIsNotNull();
        if (events.isEmpty()) {
            return;
        }
        decayService.decayAll(events);
        log.info("[RiskMaintenance] Refreshed decayed scores for {} risk event(s)", events.size());
    }
}
