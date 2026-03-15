package com.firstclub.dunning.policy;

import com.firstclub.dunning.DunningDecision;
import com.firstclub.dunning.classification.FailureCategory;
import com.firstclub.dunning.entity.DunningPolicy;
import com.firstclub.dunning.entity.DunningTerminalStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mutation-killing tests for {@link DunningPolicyEvaluator}.
 *
 * <p>Covers every method with boundary and decision-outcome assertions
 * designed to kill all PIT mutants on the STRONGER mutator set.
 */
@DisplayName("DunningPolicyEvaluator")
class DunningPolicyEvaluatorMutationTest {

    private final DunningPolicyEvaluator evaluator = new DunningPolicyEvaluator();

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static DunningPolicy policy(int maxAttempts, int graceDays,
                                        boolean fallback,
                                        DunningTerminalStatus terminal) {
        return DunningPolicy.builder()
                .id(1L).merchantId(1L).policyCode("TEST")
                .retryOffsetsJson("[60,360,1440]")
                .maxAttempts(maxAttempts)
                .graceDays(graceDays)
                .fallbackToBackupPaymentMethod(fallback)
                .statusAfterExhaustion(terminal)
                .build();
    }

    private static DunningPolicy defaultPolicy() {
        return policy(4, 7, false, DunningTerminalStatus.SUSPENDED);
    }

    // =========================================================================
    // evaluate()
    // =========================================================================

    @Nested
    @DisplayName("evaluate()")
    class EvaluateTests {

        // ── Rule 1: Non-retryable → STOP ─────────────────────────────────

        @ParameterizedTest(name = "{0} → STOP")
        @EnumSource(value = FailureCategory.class, names = {
                "CARD_STOLEN", "CARD_LOST", "FRAUDULENT", "DO_NOT_HONOR", "INVALID_ACCOUNT"
        })
        @DisplayName("non-retryable categories always return STOP regardless of state")
        void nonRetryable_alwaysStop(FailureCategory category) {
            DunningDecision decision = evaluator.evaluate(
                    category, defaultPolicy(), false, true, 5);
            assertThat(decision).isEqualTo(DunningDecision.STOP);
        }

        @Test
        @DisplayName("non-retryable STOP even when backup is available and attempts remain")
        void nonRetryable_stopEvenWithBackupAndAttempts() {
            DunningDecision decision = evaluator.evaluate(
                    FailureCategory.FRAUDULENT,
                    policy(10, 30, true, DunningTerminalStatus.SUSPENDED),
                    false, true, 10);
            assertThat(decision).isEqualTo(DunningDecision.STOP);
        }

        // ── Rule 2: Needs backup → RETRY_WITH_BACKUP or STOP ────────────

        @ParameterizedTest(name = "{0} + backup eligible → RETRY_WITH_BACKUP")
        @EnumSource(value = FailureCategory.class, names = {
                "CARD_EXPIRED", "CARD_NOT_SUPPORTED", "ISSUER_NOT_AVAILABLE"
        })
        @DisplayName("backup-needing category with all conditions met → RETRY_WITH_BACKUP")
        void backupNeeded_eligible_retryWithBackup(FailureCategory category) {
            DunningDecision decision = evaluator.evaluate(
                    category,
                    policy(4, 7, /*fallback=*/true, DunningTerminalStatus.SUSPENDED),
                    /*alreadyOnBackup=*/false, /*backupAvailable=*/true, 3);
            assertThat(decision).isEqualTo(DunningDecision.RETRY_WITH_BACKUP);
        }

        @ParameterizedTest(name = "{0} + policy disallows fallback → STOP")
        @EnumSource(value = FailureCategory.class, names = {
                "CARD_EXPIRED", "CARD_NOT_SUPPORTED", "ISSUER_NOT_AVAILABLE"
        })
        @DisplayName("backup-needing category but policy disallows → STOP")
        void backupNeeded_policyDisallows_stop(FailureCategory category) {
            DunningDecision decision = evaluator.evaluate(
                    category,
                    policy(4, 7, /*fallback=*/false, DunningTerminalStatus.SUSPENDED),
                    false, true, 3);
            assertThat(decision).isEqualTo(DunningDecision.STOP);
        }

        @Test
        @DisplayName("backup-needing but already on backup → STOP")
        void backupNeeded_alreadyOnBackup_stop() {
            DunningDecision decision = evaluator.evaluate(
                    FailureCategory.CARD_EXPIRED,
                    policy(4, 7, true, DunningTerminalStatus.SUSPENDED),
                    /*alreadyOnBackup=*/true, true, 3);
            assertThat(decision).isEqualTo(DunningDecision.STOP);
        }

        @Test
        @DisplayName("backup-needing but no backup available → STOP")
        void backupNeeded_noBackup_stop() {
            DunningDecision decision = evaluator.evaluate(
                    FailureCategory.CARD_EXPIRED,
                    policy(4, 7, true, DunningTerminalStatus.SUSPENDED),
                    false, /*backupAvailable=*/false, 3);
            assertThat(decision).isEqualTo(DunningDecision.STOP);
        }

        // ── Rule 3: Exhausted ────────────────────────────────────────────

        @ParameterizedTest(name = "{0} + 0 remaining → EXHAUSTED")
        @EnumSource(value = FailureCategory.class, names = {
                "INSUFFICIENT_FUNDS", "CARD_DECLINED_GENERIC", "GATEWAY_TIMEOUT", "UNKNOWN"
        })
        @DisplayName("retryable category with 0 remaining attempts → EXHAUSTED")
        void retryable_noRemaining_exhausted(FailureCategory category) {
            DunningDecision decision = evaluator.evaluate(
                    category, defaultPolicy(), false, false, 0);
            assertThat(decision).isEqualTo(DunningDecision.EXHAUSTED);
        }

        @Test
        @DisplayName("retryable with negative remaining → EXHAUSTED (boundary)")
        void retryable_negativeRemaining_exhausted() {
            DunningDecision decision = evaluator.evaluate(
                    FailureCategory.INSUFFICIENT_FUNDS, defaultPolicy(), false, false, -1);
            assertThat(decision).isEqualTo(DunningDecision.EXHAUSTED);
        }

        // ── Rule 4: RETRY ───────────────────────────────────────────────

        @ParameterizedTest(name = "{0} + remaining → RETRY")
        @EnumSource(value = FailureCategory.class, names = {
                "INSUFFICIENT_FUNDS", "CARD_DECLINED_GENERIC", "GATEWAY_TIMEOUT", "UNKNOWN"
        })
        @DisplayName("retryable category with remaining attempts → RETRY")
        void retryable_withRemaining_retry(FailureCategory category) {
            DunningDecision decision = evaluator.evaluate(
                    category, defaultPolicy(), false, false, 3);
            assertThat(decision).isEqualTo(DunningDecision.RETRY);
        }

        @Test
        @DisplayName("retryable with exactly 1 remaining → RETRY (boundary)")
        void retryable_exactlyOne_retry() {
            DunningDecision decision = evaluator.evaluate(
                    FailureCategory.INSUFFICIENT_FUNDS, defaultPolicy(), false, false, 1);
            assertThat(decision).isEqualTo(DunningDecision.RETRY);
        }

        @Test
        @DisplayName("evaluate returns non-null for every path")
        void neverReturnsNull() {
            // Cover all 4 paths and assert non-null
            assertThat(evaluator.evaluate(FailureCategory.CARD_STOLEN, defaultPolicy(), false, false, 0))
                    .isNotNull();
            assertThat(evaluator.evaluate(FailureCategory.CARD_EXPIRED,
                    policy(4, 7, true, DunningTerminalStatus.SUSPENDED), false, true, 3))
                    .isNotNull();
            assertThat(evaluator.evaluate(FailureCategory.CARD_EXPIRED,
                    policy(4, 7, false, DunningTerminalStatus.SUSPENDED), false, true, 3))
                    .isNotNull();
            assertThat(evaluator.evaluate(FailureCategory.INSUFFICIENT_FUNDS, defaultPolicy(), false, false, 0))
                    .isNotNull();
            assertThat(evaluator.evaluate(FailureCategory.INSUFFICIENT_FUNDS, defaultPolicy(), false, false, 1))
                    .isNotNull();
        }
    }

    // =========================================================================
    // isRetryEligible()
    // =========================================================================

    @Nested
    @DisplayName("isRetryEligible()")
    class RetryEligibleTests {

        @Test
        @DisplayName("attempt 1 of 4 → eligible")
        void attempt1of4_eligible() {
            assertThat(evaluator.isRetryEligible(1, policy(4, 7, false, DunningTerminalStatus.SUSPENDED)))
                    .isTrue();
        }

        @Test
        @DisplayName("attempt 3 of 4 → eligible (boundary −1)")
        void attempt3of4_eligible() {
            assertThat(evaluator.isRetryEligible(3, policy(4, 7, false, DunningTerminalStatus.SUSPENDED)))
                    .isTrue();
        }

        @Test
        @DisplayName("attempt 4 of 4 → NOT eligible (boundary exact)")
        void attempt4of4_notEligible() {
            assertThat(evaluator.isRetryEligible(4, policy(4, 7, false, DunningTerminalStatus.SUSPENDED)))
                    .isFalse();
        }

        @Test
        @DisplayName("attempt 5 of 4 → NOT eligible (beyond max)")
        void attempt5of4_notEligible() {
            assertThat(evaluator.isRetryEligible(5, policy(4, 7, false, DunningTerminalStatus.SUSPENDED)))
                    .isFalse();
        }

        @Test
        @DisplayName("attempt 1 of 1 → NOT eligible")
        void attempt1of1_notEligible() {
            assertThat(evaluator.isRetryEligible(1, policy(1, 7, false, DunningTerminalStatus.SUSPENDED)))
                    .isFalse();
        }
    }

    // =========================================================================
    // isWithinGraceWindow()
    // =========================================================================

    @Nested
    @DisplayName("isWithinGraceWindow()")
    class GraceWindowTests {

        private final LocalDateTime failureTime = LocalDateTime.of(2026, 1, 1, 12, 0);

        @Test
        @DisplayName("scheduled exactly at grace deadline → within window")
        void exactDeadline_withinWindow() {
            DunningPolicy p = policy(4, 7, false, DunningTerminalStatus.SUSPENDED);
            LocalDateTime atDeadline = failureTime.plusDays(7);
            assertThat(evaluator.isWithinGraceWindow(atDeadline, failureTime, p)).isTrue();
        }

        @Test
        @DisplayName("scheduled 1 second after grace deadline → outside window")
        void afterDeadline_outsideWindow() {
            DunningPolicy p = policy(4, 7, false, DunningTerminalStatus.SUSPENDED);
            LocalDateTime afterDeadline = failureTime.plusDays(7).plusSeconds(1);
            assertThat(evaluator.isWithinGraceWindow(afterDeadline, failureTime, p)).isFalse();
        }

        @Test
        @DisplayName("scheduled 1 day before deadline → within window")
        void beforeDeadline_withinWindow() {
            DunningPolicy p = policy(4, 7, false, DunningTerminalStatus.SUSPENDED);
            LocalDateTime before = failureTime.plusDays(6);
            assertThat(evaluator.isWithinGraceWindow(before, failureTime, p)).isTrue();
        }

        @Test
        @DisplayName("scheduled at failure time → within window")
        void atFailureTime_withinWindow() {
            DunningPolicy p = policy(4, 7, false, DunningTerminalStatus.SUSPENDED);
            assertThat(evaluator.isWithinGraceWindow(failureTime, failureTime, p)).isTrue();
        }

        @Test
        @DisplayName("grace period of 0 days — only exact failure time is within")
        void zeroDayGrace_onlyExactTime() {
            DunningPolicy p = policy(4, 0, false, DunningTerminalStatus.SUSPENDED);
            assertThat(evaluator.isWithinGraceWindow(failureTime, failureTime, p)).isTrue();
            assertThat(evaluator.isWithinGraceWindow(failureTime.plusMinutes(1), failureTime, p)).isFalse();
        }
    }

    // =========================================================================
    // isTerminalFailure()
    // =========================================================================

    @Nested
    @DisplayName("isTerminalFailure()")
    class TerminalFailureTests {

        @ParameterizedTest(name = "{0} → terminal")
        @EnumSource(value = FailureCategory.class, names = {
                "CARD_STOLEN", "CARD_LOST", "FRAUDULENT", "DO_NOT_HONOR", "INVALID_ACCOUNT"
        })
        @DisplayName("non-retryable categories are terminal")
        void nonRetryable_isTerminal(FailureCategory cat) {
            assertThat(evaluator.isTerminalFailure(cat)).isTrue();
        }

        @ParameterizedTest(name = "{0} → NOT terminal")
        @EnumSource(value = FailureCategory.class, names = {
                "INSUFFICIENT_FUNDS", "CARD_DECLINED_GENERIC", "GATEWAY_TIMEOUT",
                "CARD_EXPIRED", "CARD_NOT_SUPPORTED", "ISSUER_NOT_AVAILABLE", "UNKNOWN"
        })
        @DisplayName("retryable and backup categories are NOT terminal")
        void retryable_notTerminal(FailureCategory cat) {
            assertThat(evaluator.isTerminalFailure(cat)).isFalse();
        }
    }

    // =========================================================================
    // needsBackupMethod()
    // =========================================================================

    @Nested
    @DisplayName("needsBackupMethod()")
    class NeedsBackupTests {

        @ParameterizedTest(name = "{0} → needs backup")
        @EnumSource(value = FailureCategory.class, names = {
                "CARD_EXPIRED", "CARD_NOT_SUPPORTED", "ISSUER_NOT_AVAILABLE"
        })
        @DisplayName("backup-needing categories return true")
        void backupCategories_true(FailureCategory cat) {
            assertThat(evaluator.needsBackupMethod(cat)).isTrue();
        }

        @ParameterizedTest(name = "{0} → does NOT need backup")
        @EnumSource(value = FailureCategory.class, names = {
                "INSUFFICIENT_FUNDS", "CARD_DECLINED_GENERIC", "GATEWAY_TIMEOUT",
                "CARD_STOLEN", "CARD_LOST", "FRAUDULENT", "DO_NOT_HONOR",
                "INVALID_ACCOUNT", "UNKNOWN"
        })
        @DisplayName("non-backup categories return false")
        void nonBackupCategories_false(FailureCategory cat) {
            assertThat(evaluator.needsBackupMethod(cat)).isFalse();
        }
    }

    // =========================================================================
    // shouldAttemptBackup()
    // =========================================================================

    @Nested
    @DisplayName("shouldAttemptBackup()")
    class ShouldAttemptBackupTests {

        @Test
        @DisplayName("all conditions met → true")
        void allConditionsMet_true() {
            DunningPolicy p = policy(4, 7, /*fallback=*/true, DunningTerminalStatus.SUSPENDED);
            assertThat(evaluator.shouldAttemptBackup(p, false, true)).isTrue();
        }

        @Test
        @DisplayName("policy disallows fallback → false")
        void policyDisallows_false() {
            DunningPolicy p = policy(4, 7, /*fallback=*/false, DunningTerminalStatus.SUSPENDED);
            assertThat(evaluator.shouldAttemptBackup(p, false, true)).isFalse();
        }

        @Test
        @DisplayName("already on backup → false")
        void alreadyOnBackup_false() {
            DunningPolicy p = policy(4, 7, true, DunningTerminalStatus.SUSPENDED);
            assertThat(evaluator.shouldAttemptBackup(p, /*alreadyOnBackup=*/true, true)).isFalse();
        }

        @Test
        @DisplayName("no backup available → false")
        void noBackupAvailable_false() {
            DunningPolicy p = policy(4, 7, true, DunningTerminalStatus.SUSPENDED);
            assertThat(evaluator.shouldAttemptBackup(p, false, /*backupAvailable=*/false)).isFalse();
        }

        @Test
        @DisplayName("all conditions false → false")
        void allConditionsFalse_false() {
            DunningPolicy p = policy(4, 7, false, DunningTerminalStatus.SUSPENDED);
            assertThat(evaluator.shouldAttemptBackup(p, true, false)).isFalse();
        }
    }

    // =========================================================================
    // resolveTerminalStatus()
    // =========================================================================

    @Nested
    @DisplayName("resolveTerminalStatus()")
    class ResolveTerminalStatusTests {

        @Test
        @DisplayName("policy with SUSPENDED → SUSPENDED")
        void suspended() {
            DunningPolicy p = policy(4, 7, false, DunningTerminalStatus.SUSPENDED);
            assertThat(evaluator.resolveTerminalStatus(p)).isEqualTo(DunningTerminalStatus.SUSPENDED);
        }

        @Test
        @DisplayName("policy with CANCELLED → CANCELLED")
        void cancelled() {
            DunningPolicy p = policy(4, 7, false, DunningTerminalStatus.CANCELLED);
            assertThat(evaluator.resolveTerminalStatus(p)).isEqualTo(DunningTerminalStatus.CANCELLED);
        }

        @Test
        @DisplayName("policy with null → defaults to SUSPENDED")
        void nullStatus_defaultsSuspended() {
            DunningPolicy p = policy(4, 7, false, null);
            assertThat(evaluator.resolveTerminalStatus(p)).isEqualTo(DunningTerminalStatus.SUSPENDED);
        }

        @Test
        @DisplayName("result is never null")
        void neverNull() {
            assertThat(evaluator.resolveTerminalStatus(policy(4, 7, false, DunningTerminalStatus.CANCELLED)))
                    .isNotNull();
            assertThat(evaluator.resolveTerminalStatus(policy(4, 7, false, null)))
                    .isNotNull();
        }
    }

    // =========================================================================
    // computeSchedulableAttempts()
    // =========================================================================

    @Nested
    @DisplayName("computeSchedulableAttempts()")
    class ComputeSchedulableAttemptsTests {

        @Test
        @DisplayName("all offsets within grace → all schedulable")
        void allWithinGrace() {
            // 7 days = 10080 minutes; offsets [60, 360, 1440] all fit
            DunningPolicy p = policy(4, 7, false, DunningTerminalStatus.SUSPENDED);
            assertThat(evaluator.computeSchedulableAttempts(List.of(60, 360, 1440), p)).isEqualTo(3);
        }

        @Test
        @DisplayName("some offsets exceed grace → only those within")
        void someExceedGrace() {
            // 1 day = 1440 minutes; offsets [60, 360, 1440, 4320]
            // 60 ≤ 1440 ✓, 360 ≤ 1440 ✓, 1440 ≤ 1440 ✓, 4320 > 1440 ✗
            DunningPolicy p = policy(10, 1, false, DunningTerminalStatus.SUSPENDED);
            assertThat(evaluator.computeSchedulableAttempts(List.of(60, 360, 1440, 4320), p)).isEqualTo(3);
        }

        @Test
        @DisplayName("first offset exceeds grace → 0 schedulable")
        void firstExceedsGrace() {
            DunningPolicy p = policy(4, 0, false, DunningTerminalStatus.SUSPENDED);
            // 0 days = 0 minutes; offset 60 > 0
            assertThat(evaluator.computeSchedulableAttempts(List.of(60, 360), p)).isEqualTo(0);
        }

        @Test
        @DisplayName("maxAttempts limits count even if all offsets fit")
        void maxAttemptsLimits() {
            // 7 days grace; 5 offsets all within window but maxAttempts=2
            DunningPolicy p = policy(2, 7, false, DunningTerminalStatus.SUSPENDED);
            assertThat(evaluator.computeSchedulableAttempts(List.of(60, 360, 1440, 4320, 8640), p)).isEqualTo(2);
        }

        @Test
        @DisplayName("empty offsets → 0")
        void emptyOffsets() {
            assertThat(evaluator.computeSchedulableAttempts(List.of(), defaultPolicy())).isEqualTo(0);
        }

        @Test
        @DisplayName("null offsets → 0")
        void nullOffsets() {
            assertThat(evaluator.computeSchedulableAttempts(null, defaultPolicy())).isEqualTo(0);
        }

        @Test
        @DisplayName("offset exactly at boundary (grace minutes) → schedulable")
        void offsetExactlyAtBoundary() {
            // 1 day = 1440 minutes; offset = 1440 → should be schedulable (<=)
            DunningPolicy p = policy(4, 1, false, DunningTerminalStatus.SUSPENDED);
            assertThat(evaluator.computeSchedulableAttempts(List.of(1440), p)).isEqualTo(1);
        }

        @Test
        @DisplayName("offset one minute beyond boundary → NOT schedulable")
        void offsetOneBeyondBoundary() {
            // 1 day = 1440 minutes; offset = 1441 → not schedulable (>)
            DunningPolicy p = policy(4, 1, false, DunningTerminalStatus.SUSPENDED);
            assertThat(evaluator.computeSchedulableAttempts(List.of(1441), p)).isEqualTo(0);
        }

        @Test
        @DisplayName("single offset within grace → 1")
        void singleOffsetWithin() {
            DunningPolicy p = policy(4, 7, false, DunningTerminalStatus.SUSPENDED);
            assertThat(evaluator.computeSchedulableAttempts(List.of(60), p)).isEqualTo(1);
        }

        @Test
        @DisplayName("maxAttempts=0 → always 0")
        void maxAttemptsZero() {
            DunningPolicy p = policy(0, 7, false, DunningTerminalStatus.SUSPENDED);
            assertThat(evaluator.computeSchedulableAttempts(List.of(60, 360), p)).isEqualTo(0);
        }

        @Test
        @DisplayName("grace period large enough for all offsets and maxAttempts equals offsets count")
        void graceLargeMaxEqualsOffsets() {
            DunningPolicy p = policy(3, 30, false, DunningTerminalStatus.SUSPENDED);
            assertThat(evaluator.computeSchedulableAttempts(List.of(60, 360, 1440), p)).isEqualTo(3);
        }
    }
}
