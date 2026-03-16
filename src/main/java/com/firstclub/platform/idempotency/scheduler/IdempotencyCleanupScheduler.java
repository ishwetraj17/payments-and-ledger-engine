package com.firstclub.platform.idempotency.scheduler;

import com.firstclub.platform.idempotency.service.IdempotencyRecordService;
import com.firstclub.platform.scheduler.PrimaryOnlySchedulerGuard;
import com.firstclub.platform.scheduler.lock.SchedulerLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Supplementary scheduler for idempotency key hygiene.
 *
 * <p>Complements {@link com.firstclub.platform.idempotency.IdempotencyCleanupJob}
 * (which handles TTL-based expiry) by adding stuck-PROCESSING recovery:
 *
 * <ul>
 *   <li>{@link #resetStuckProcessing()} — finds PROCESSING records that have been
 *       in-flight longer than {@code app.idempotency.stuck-processing-threshold-minutes}
 *       (default: 5 min) and transitions them to {@code FAILED_RETRYABLE}.  This
 *       handles crash/restart scenarios where the completion callback never fired.
 *       Runs every {@code app.idempotency.stuck-cleanup-interval-ms} (default: 60 s).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyCleanupScheduler {

    private static final String SCHEDULER_NAME = "idempotency-stuck-cleanup";

    private final IdempotencyRecordService recordService;
    private final SchedulerLockService schedulerLockService;
    private final PrimaryOnlySchedulerGuard primaryOnlySchedulerGuard;

    @Value("${app.idempotency.stuck-processing-threshold-minutes:5}")
    private int stuckThresholdMinutes;

    /**
     * Resets stuck PROCESSING records to {@code FAILED_RETRYABLE}.
     *
     * <p>A record is considered stuck when it has been in {@code PROCESSING} state
     * longer than {@link #stuckThresholdMinutes} minutes. Clients that hold such
     * an Idempotency-Key may safely retry — the operation will be re-executed from
     * scratch and will produce a new authoritative result.
     *
     * @return number of records reset (useful for testing)
     */
    @Scheduled(fixedDelayString = "${app.idempotency.stuck-cleanup-interval-ms:60000}")
    @Transactional
    public int resetStuckProcessing() {
        if (!primaryOnlySchedulerGuard.canRunScheduler(SCHEDULER_NAME)) {
            return 0;
        }
        if (!schedulerLockService.tryAcquireForBatch(SCHEDULER_NAME)) {
            log.debug("[{}] advisory lock not acquired — another node is running this batch", SCHEDULER_NAME);
            return 0;
        }
        Duration threshold = Duration.ofMinutes(stuckThresholdMinutes);
        return recordService.resetStuckProcessing(threshold);
    }
}
