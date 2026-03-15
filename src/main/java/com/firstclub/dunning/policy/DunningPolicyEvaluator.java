package com.firstclub.dunning.policy;

import com.firstclub.dunning.DunningDecision;
import com.firstclub.dunning.classification.FailureCategory;
import com.firstclub.dunning.entity.DunningPolicy;
import com.firstclub.dunning.entity.DunningTerminalStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Pure policy-evaluation logic for dunning decisions.
 *
 * <p>This component encapsulates the rules that determine whether a failed
 * payment should be retried, whether a backup payment method should be
 * attempted, or whether the dunning run should terminate.  All inputs are
 * passed as method arguments; the evaluator holds no mutable state and has
 * no repository dependencies, making it trivially unit-testable.
 *
 * <h3>Decision rules (priority order)</h3>
 * <ol>
 *   <li><b>Terminal failure</b> — failure category is non-retryable → {@link DunningDecision#STOP}</li>
 *   <li><b>Needs backup</b> — category requires a different instrument:
 *       <ul>
 *         <li>backup eligible → {@link DunningDecision#RETRY_WITH_BACKUP}</li>
 *         <li>not eligible → {@link DunningDecision#STOP}</li>
 *       </ul>
 *   </li>
 *   <li><b>Exhausted</b> — no remaining scheduled attempts → {@link DunningDecision#EXHAUSTED}</li>
 *   <li><b>Default</b> — retryable failure → {@link DunningDecision#RETRY}</li>
 * </ol>
 */
@Component
public class DunningPolicyEvaluator {

    /** Categories that must never be retried regardless of remaining attempts. */
    private static final Set<FailureCategory> NON_RETRYABLE = Set.of(
            FailureCategory.CARD_STOLEN,
            FailureCategory.CARD_LOST,
            FailureCategory.FRAUDULENT,
            FailureCategory.DO_NOT_HONOR,
            FailureCategory.INVALID_ACCOUNT
    );

    /** Categories where only a different payment instrument can cure the failure. */
    private static final Set<FailureCategory> NEEDS_BACKUP = Set.of(
            FailureCategory.CARD_EXPIRED,
            FailureCategory.CARD_NOT_SUPPORTED,
            FailureCategory.ISSUER_NOT_AVAILABLE
    );

    // ── Primary decision method ──────────────────────────────────────────────

    /**
     * Evaluate the policy and return a dunning decision.
     *
     * @param category          classified failure category
     * @param policy            the governing dunning policy
     * @param alreadyOnBackup   whether the attempt already used the backup PM
     * @param backupAvailable   whether a backup payment method exists
     * @param remainingAttempts number of SCHEDULED attempts still in the queue
     * @return the decision — never {@code null}
     */
    public DunningDecision evaluate(FailureCategory category,
                                    DunningPolicy policy,
                                    boolean alreadyOnBackup,
                                    boolean backupAvailable,
                                    long remainingAttempts) {

        // Rule 1 — non-retryable: stop immediately
        if (isTerminalFailure(category)) {
            return DunningDecision.STOP;
        }

        // Rule 2 — needs backup instrument
        if (needsBackupMethod(category)) {
            if (shouldAttemptBackup(policy, alreadyOnBackup, backupAvailable)) {
                return DunningDecision.RETRY_WITH_BACKUP;
            }
            return DunningDecision.STOP;
        }

        // Rule 3 — all attempts exhausted
        if (remainingAttempts <= 0) {
            return DunningDecision.EXHAUSTED;
        }

        // Rule 4 — ordinary retryable failure
        return DunningDecision.RETRY;
    }

    // ── Retry eligibility ────────────────────────────────────────────────────

    /**
     * Check whether the given attempt number is within the policy's retry limit.
     *
     * @param currentAttempt 1-based attempt number of the current attempt
     * @param policy         the dunning policy
     * @return {@code true} if more retries are allowed
     */
    public boolean isRetryEligible(int currentAttempt, DunningPolicy policy) {
        return currentAttempt < policy.getMaxAttempts();
    }

    // ── Grace-window check ───────────────────────────────────────────────────

    /**
     * Check whether a scheduled retry time falls within the grace window.
     *
     * @param scheduledTime the proposed retry time
     * @param failureTime   the time of the original payment failure
     * @param policy        the dunning policy defining the grace window
     * @return {@code true} if the scheduled time is within the grace window
     */
    public boolean isWithinGraceWindow(LocalDateTime scheduledTime,
                                       LocalDateTime failureTime,
                                       DunningPolicy policy) {
        LocalDateTime graceDeadline = failureTime.plusDays(policy.getGraceDays());
        return !scheduledTime.isAfter(graceDeadline);
    }

    // ── Failure-category queries ─────────────────────────────────────────────

    /**
     * @return {@code true} if the category is non-retryable (terminal)
     */
    public boolean isTerminalFailure(FailureCategory category) {
        return NON_RETRYABLE.contains(category);
    }

    /**
     * @return {@code true} if the category requires a backup payment method
     */
    public boolean needsBackupMethod(FailureCategory category) {
        return NEEDS_BACKUP.contains(category);
    }

    // ── Backup eligibility ───────────────────────────────────────────────────

    /**
     * Determine whether the backup payment method should be attempted.
     *
     * <p>Three conditions must all hold:
     * <ol>
     *   <li>Policy allows fallback to backup</li>
     *   <li>The current attempt is not already on the backup method</li>
     *   <li>A backup method actually exists</li>
     * </ol>
     *
     * @param policy          the dunning policy
     * @param alreadyOnBackup whether the current attempt already used backup
     * @param backupAvailable whether a backup PM exists for the subscription
     * @return {@code true} if backup should be attempted
     */
    public boolean shouldAttemptBackup(DunningPolicy policy,
                                       boolean alreadyOnBackup,
                                       boolean backupAvailable) {
        return policy.isFallbackToBackupPaymentMethod()
                && !alreadyOnBackup
                && backupAvailable;
    }

    // ── Terminal status resolution ───────────────────────────────────────────

    /**
     * Resolve the terminal subscription status from the policy.
     *
     * <p>Falls back to {@link DunningTerminalStatus#SUSPENDED} when the policy
     * has no explicit terminal status configured.
     *
     * @param policy the dunning policy
     * @return the terminal status to apply — never {@code null}
     */
    public DunningTerminalStatus resolveTerminalStatus(DunningPolicy policy) {
        DunningTerminalStatus status = policy.getStatusAfterExhaustion();
        return status != null ? status : DunningTerminalStatus.SUSPENDED;
    }

    // ── Schedule computation ─────────────────────────────────────────────────

    /**
     * Compute how many retry attempts from the offset list can be scheduled
     * within the policy's constraints (max attempts and grace window).
     *
     * <p>Offsets are assumed to be in ascending order.  The method stops at the
     * first offset that exceeds the grace window (in minutes) or once
     * {@code maxAttempts} has been reached.
     *
     * @param offsets delay offsets in minutes (ascending)
     * @param policy  the dunning policy
     * @return the number of schedulable attempts (≥ 0)
     */
    public int computeSchedulableAttempts(List<Integer> offsets, DunningPolicy policy) {
        if (offsets == null || offsets.isEmpty()) {
            return 0;
        }
        int limit = Math.min(offsets.size(), policy.getMaxAttempts());
        long graceMinutes = (long) policy.getGraceDays() * 24 * 60;
        int schedulable = 0;
        for (int i = 0; i < limit; i++) {
            if (offsets.get(i) <= graceMinutes) {
                schedulable++;
            } else {
                break;
            }
        }
        return schedulable;
    }
}
