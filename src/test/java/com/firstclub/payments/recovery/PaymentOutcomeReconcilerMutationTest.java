package com.firstclub.payments.recovery;

import com.firstclub.payments.entity.FailureCategory;
import com.firstclub.payments.entity.PaymentAttempt;
import com.firstclub.payments.entity.PaymentAttemptStatus;
import com.firstclub.payments.entity.PaymentIntentStatusV2;
import com.firstclub.payments.entity.PaymentIntentV2;
import com.firstclub.payments.gateway.GatewayResult;
import com.firstclub.payments.gateway.GatewayResultStatus;
import com.firstclub.payments.gateway.GatewayStatusResolver;
import com.firstclub.payments.repository.PaymentAttemptRepository;
import com.firstclub.payments.repository.PaymentIntentV2Repository;
import com.firstclub.payments.service.PaymentAttemptService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mutation-killing tests for {@link PaymentOutcomeReconciler}.
 *
 * <p>Targets 10 surviving mutants from the PIT baseline (52% → 100%):
 * <ul>
 *   <li>reconcile/success — intent.lastSuccessfulAttemptId, status, reconciliationState (lines 85-87)</li>
 *   <li>reconcile/failed — intent.reconciliationState, ifPresent call, null failureCategory fallback (lines 97,103-104)</li>
 *   <li>reconcile/unknown — intent.reconciliationState (line 115)</li>
 *   <li>markReconciled — attempt.status, attempt.completedAt (lines 149-150)</li>
 *   <li>reconcileIntent — return value (line 143)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentOutcomeReconciler — Mutation-killing tests")
class PaymentOutcomeReconcilerMutationTest {

    @Mock private GatewayStatusResolver gatewayStatusResolver;
    @Mock private PaymentAttemptService paymentAttemptService;
    @Mock private PaymentAttemptRepository paymentAttemptRepository;
    @Mock private PaymentIntentV2Repository paymentIntentV2Repository;

    @InjectMocks
    private PaymentOutcomeReconciler reconciler;

    // ─────────────────────────────────────────────────────────────────────────
    // reconcile — gateway SUCCEEDED: assert exact intent state transitions
    // Kills mutants on lines 85, 86, 87
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("reconcile — gateway SUCCEEDED intent state")
    class ReconcileSuccessIntentState {

        @Test
        @DisplayName("success sets lastSuccessfulAttemptId on intent")
        void success_setsLastSuccessfulAttemptId() {
            PaymentAttempt attempt = unknownAttempt(1L, 10L);
            PaymentIntentV2 intent = intentOf(10L, PaymentIntentStatusV2.PROCESSING);

            when(gatewayStatusResolver.resolveStatus(attempt))
                    .thenReturn(GatewayResult.succeeded("TXN-1", "200", 100L));
            when(paymentAttemptService.markSucceeded(anyLong(), anyLong(), any(), anyLong()))
                    .thenReturn(attempt);
            when(paymentIntentV2Repository.findById(10L))
                    .thenReturn(Optional.of(intent));

            reconciler.reconcile(attempt);

            assertThat(intent.getLastSuccessfulAttemptId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("success sets intent status to SUCCEEDED")
        void success_setsIntentStatusSucceeded() {
            PaymentAttempt attempt = unknownAttempt(2L, 20L);
            PaymentIntentV2 intent = intentOf(20L, PaymentIntentStatusV2.PROCESSING);

            when(gatewayStatusResolver.resolveStatus(attempt))
                    .thenReturn(GatewayResult.succeeded("TXN-2", "200", 80L));
            when(paymentAttemptService.markSucceeded(anyLong(), anyLong(), any(), anyLong()))
                    .thenReturn(attempt);
            when(paymentIntentV2Repository.findById(20L))
                    .thenReturn(Optional.of(intent));

            reconciler.reconcile(attempt);

            assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatusV2.SUCCEEDED);
        }

        @Test
        @DisplayName("success sets reconciliationState to RECONCILED_SUCCESS")
        void success_setsReconciliationState() {
            PaymentAttempt attempt = unknownAttempt(3L, 30L);
            PaymentIntentV2 intent = intentOf(30L, PaymentIntentStatusV2.PROCESSING);

            when(gatewayStatusResolver.resolveStatus(attempt))
                    .thenReturn(GatewayResult.succeeded("TXN-3", "200", 90L));
            when(paymentAttemptService.markSucceeded(anyLong(), anyLong(), any(), anyLong()))
                    .thenReturn(attempt);
            when(paymentIntentV2Repository.findById(30L))
                    .thenReturn(Optional.of(intent));

            reconciler.reconcile(attempt);

            assertThat(intent.getReconciliationState()).isEqualTo("RECONCILED_SUCCESS");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // reconcile — gateway FAILED: assert reconciliationState and ifPresent
    // Kills mutants on lines 103, 104
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("reconcile — gateway FAILED intent state")
    class ReconcileFailedIntentState {

        @Test
        @DisplayName("failed sets reconciliationState to RECONCILED_FAILED")
        void failed_setsReconciliationState() {
            PaymentAttempt attempt = unknownAttempt(4L, 40L);
            PaymentIntentV2 intent = intentOf(40L, PaymentIntentStatusV2.PROCESSING);

            when(gatewayStatusResolver.resolveStatus(attempt))
                    .thenReturn(GatewayResult.failed(
                            FailureCategory.ISSUER_DECLINE, "Declined", "DECLINED", 70L));
            when(paymentAttemptService.markFailed(anyLong(), anyLong(), any(), any(), any(), eq(false), anyLong()))
                    .thenReturn(attempt);
            when(paymentIntentV2Repository.findById(40L))
                    .thenReturn(Optional.of(intent));

            reconciler.reconcile(attempt);

            assertThat(intent.getReconciliationState()).isEqualTo("RECONCILED_FAILED");
            verify(paymentIntentV2Repository).save(intent);
        }

        @Test
        @DisplayName("failed with null failureCategory falls back to GATEWAY_ERROR")
        void failed_nullCategory_fallsBackToGatewayError() {
            PaymentAttempt attempt = unknownAttempt(5L, 50L);
            PaymentIntentV2 intent = intentOf(50L, PaymentIntentStatusV2.PROCESSING);

            // Build a GatewayResult with null failureCategory
            GatewayResult noCategory = new GatewayResult(
                    GatewayResultStatus.FAILED,
                    null, null,
                    "ERR-500", "Internal error", null,
                    60L, null  // null failureCategory
            );

            when(gatewayStatusResolver.resolveStatus(attempt))
                    .thenReturn(noCategory);
            when(paymentAttemptService.markFailed(anyLong(), anyLong(), any(), any(), any(), eq(false), anyLong()))
                    .thenReturn(attempt);
            when(paymentIntentV2Repository.findById(50L))
                    .thenReturn(Optional.of(intent));

            reconciler.reconcile(attempt);

            // Assert FailureCategory.GATEWAY_ERROR was used as fallback
            verify(paymentAttemptService).markFailed(
                    eq(5L), eq(50L), eq("ERR-500"), eq("Internal error"),
                    eq(FailureCategory.GATEWAY_ERROR), eq(false), eq(60L));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // reconcile — gateway still UNKNOWN: assert reconciliationState + markReconciled
    // Kills mutants on lines 115, 149, 150
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("reconcile — gateway still UNKNOWN state transitions")
    class ReconcileUnknownState {

        @Test
        @DisplayName("unknown sets intent reconciliationState to REQUIRES_MANUAL_REVIEW")
        void unknown_setsReconciliationState() {
            PaymentAttempt attempt = unknownAttempt(6L, 60L);
            PaymentIntentV2 intent = intentOf(60L, PaymentIntentStatusV2.PROCESSING);

            when(gatewayStatusResolver.resolveStatus(attempt))
                    .thenReturn(GatewayResult.unknown("TXN-X", "{}", 3000L));
            when(paymentIntentV2Repository.findById(60L))
                    .thenReturn(Optional.of(intent));

            reconciler.reconcile(attempt);

            assertThat(intent.getReconciliationState()).isEqualTo("REQUIRES_MANUAL_REVIEW");
        }

        @Test
        @DisplayName("unknown sets attempt status to RECONCILED")
        void unknown_setsAttemptStatusReconciled() {
            PaymentAttempt attempt = unknownAttempt(7L, 70L);
            PaymentIntentV2 intent = intentOf(70L, PaymentIntentStatusV2.PROCESSING);

            when(gatewayStatusResolver.resolveStatus(attempt))
                    .thenReturn(GatewayResult.unknown("TXN-Y", "{}", 5000L));
            when(paymentIntentV2Repository.findById(70L))
                    .thenReturn(Optional.of(intent));

            reconciler.reconcile(attempt);

            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.RECONCILED);
        }

        @Test
        @DisplayName("unknown sets completedAt on attempt")
        void unknown_setsCompletedAt() {
            PaymentAttempt attempt = unknownAttempt(8L, 80L);
            PaymentIntentV2 intent = intentOf(80L, PaymentIntentStatusV2.PROCESSING);

            when(gatewayStatusResolver.resolveStatus(attempt))
                    .thenReturn(GatewayResult.unknown("TXN-Z", "{}", 4000L));
            when(paymentIntentV2Repository.findById(80L))
                    .thenReturn(Optional.of(intent));

            reconciler.reconcile(attempt);

            assertThat(attempt.getCompletedAt()).isNotNull();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // reconcileIntent — return value
    // Kills mutant on line 143
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("reconcileIntent — return value")
    class ReconcileIntentReturnValue {

        @Test
        @DisplayName("reconcileIntent returns exact count of UNKNOWN attempts processed")
        void reconcileIntent_returnsExactCount() {
            Long intentId = 200L;
            PaymentAttempt a1 = unknownAttempt(11L, intentId);
            PaymentAttempt a2 = unknownAttempt(12L, intentId);
            PaymentAttempt a3 = unknownAttempt(13L, intentId);

            when(paymentAttemptRepository.findByPaymentIntentIdAndStatus(
                    intentId, PaymentAttemptStatus.UNKNOWN))
                    .thenReturn(List.of(a1, a2, a3));

            when(gatewayStatusResolver.resolveStatus(any()))
                    .thenReturn(GatewayResult.succeeded("TXN", "200", 50L));
            when(paymentAttemptService.markSucceeded(anyLong(), anyLong(), any(), anyLong()))
                    .thenReturn(a1);
            when(paymentIntentV2Repository.findById(intentId))
                    .thenReturn(Optional.of(intentOf(intentId, PaymentIntentStatusV2.PROCESSING)));

            int count = reconciler.reconcileIntent(intentId);

            assertThat(count).isEqualTo(3);
        }

        @Test
        @DisplayName("reconcileIntent returns zero for empty UNKNOWN set")
        void reconcileIntent_emptySet_returnsZero() {
            when(paymentAttemptRepository.findByPaymentIntentIdAndStatus(
                    999L, PaymentAttemptStatus.UNKNOWN))
                    .thenReturn(List.of());

            int count = reconciler.reconcileIntent(999L);

            assertThat(count).isEqualTo(0);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static PaymentAttempt unknownAttempt(Long id, Long intentId) {
        PaymentIntentV2 intent = intentOf(intentId, PaymentIntentStatusV2.PROCESSING);
        PaymentAttempt attempt = PaymentAttempt.builder()
                .paymentIntent(intent)
                .attemptNumber(1)
                .gatewayName("TEST")
                .build();
        attempt.setStatus(PaymentAttemptStatus.UNKNOWN);
        setField(attempt, "id", id);
        return attempt;
    }

    private static PaymentIntentV2 intentOf(Long id, PaymentIntentStatusV2 status) {
        PaymentIntentV2 intent = new PaymentIntentV2();
        setField(intent, "id", id);
        intent.setStatus(status);
        return intent;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
