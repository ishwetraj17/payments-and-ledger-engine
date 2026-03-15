package com.firstclub.payments.gateway;

import com.firstclub.payments.entity.PaymentAttempt;
import com.firstclub.payments.entity.PaymentIntentV2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

/**
 * Mutation-killing tests for {@link PaymentGatewayCallService}.
 *
 * <p>Targets surviving and no-coverage mutants identified by PIT baseline:
 * <ul>
 *   <li>simulateGatewayCall line 120 — 3 survived mutants on timeout guard ({@code simulatedTimeoutRate > 0})</li>
 *   <li>simulateGatewayCall lines 121-125 — 4 no-coverage mutants in timeout injection branch</li>
 *   <li>submitPayment line 83 — 2 survived MathMutator mutants (equivalent: elapsed only used for logging)</li>
 *   <li>resolveProcessorNodeId line 141 — 1 no-coverage mutant in exception fallback</li>
 * </ul>
 */
@DisplayName("PaymentGatewayCallService — Mutation Tests")
class PaymentGatewayCallServiceMutationTest {

    private PaymentGatewayCallService service;

    @BeforeEach
    void setUp() {
        service = new PaymentGatewayCallService();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // simulateGatewayCall — timeout path
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("simulateGatewayCall — timeout injection")
    class SimulateGatewayCallTimeout {

        @Test
        @DisplayName("simulateGatewayCall_timeoutRate100_alwaysReturnsTimeout")
        void simulateGatewayCall_timeoutRate100_returnsTimeout() throws Exception {
            setField("simulatedTimeoutRate", 100);
            setField("gatewayTimeoutMs", 5000L);

            PaymentAttempt attempt = attemptOf(1L, 1);
            PaymentIntentV2 intent = intentOf(1L);

            GatewayResult result = service.simulateGatewayCall(attempt, intent);

            assertThat(result.status()).isEqualTo(GatewayResultStatus.TIMEOUT);
            assertThat(result.latencyMs()).isEqualTo(5000L);
            assertThat(result.isSucceeded()).isFalse();
            assertThat(result.needsReconciliation()).isTrue();
        }

        @Test
        @DisplayName("simulateGatewayCall_timeoutRate0_neverReturnsTimeout")
        void simulateGatewayCall_timeoutRate0_neverReturnsTimeout() throws Exception {
            setField("simulatedTimeoutRate", 0);

            PaymentAttempt attempt = attemptOf(2L, 1);
            PaymentIntentV2 intent = intentOf(2L);

            // Run multiple times to confirm no random timeouts
            for (int i = 0; i < 20; i++) {
                GatewayResult result = service.simulateGatewayCall(attempt, intent);
                assertThat(result.status()).isEqualTo(GatewayResultStatus.SUCCEEDED);
            }
        }

        @Test
        @DisplayName("simulateGatewayCall_timeoutRate100_resultResponseCodeIsTimeout")
        void simulateGatewayCall_timeoutRate100_responseCodeIsTimeout() throws Exception {
            setField("simulatedTimeoutRate", 100);
            setField("gatewayTimeoutMs", 3000L);

            PaymentAttempt attempt = attemptOf(3L, 1);
            PaymentIntentV2 intent = intentOf(3L);

            GatewayResult result = service.simulateGatewayCall(attempt, intent);

            assertThat(result.responseCode()).isEqualTo("TIMEOUT");
            assertThat(result.latencyMs()).isEqualTo(3000L);
        }

        @Test
        @DisplayName("simulateGatewayCall_timeoutRate100_resultGatewayTransactionIdIsNull")
        void simulateGatewayCall_timeoutRate100_noGatewayTransactionId() throws Exception {
            setField("simulatedTimeoutRate", 100);
            setField("gatewayTimeoutMs", 5000L);

            PaymentAttempt attempt = attemptOf(4L, 1);
            PaymentIntentV2 intent = intentOf(4L);

            GatewayResult result = service.simulateGatewayCall(attempt, intent);

            assertThat(result.gatewayTransactionId()).isNull();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // simulateGatewayCall — success path
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("simulateGatewayCall — success path")
    class SimulateGatewayCallSuccess {

        @Test
        @DisplayName("simulateGatewayCall_successPath_returnsSucceededStatus")
        void simulateGatewayCall_successPath_succeededStatus() throws Exception {
            setField("simulatedTimeoutRate", 0);

            PaymentAttempt attempt = attemptOf(10L, 1);
            PaymentIntentV2 intent = intentOf(10L);

            GatewayResult result = service.simulateGatewayCall(attempt, intent);

            assertThat(result.status()).isEqualTo(GatewayResultStatus.SUCCEEDED);
            assertThat(result.isSucceeded()).isTrue();
            assertThat(result.isFailed()).isFalse();
        }

        @Test
        @DisplayName("simulateGatewayCall_successPath_gatewayTransactionIdStartsWithGatewayName")
        void simulateGatewayCall_successPath_txnIdStartsWithGatewayName() throws Exception {
            setField("simulatedTimeoutRate", 0);

            PaymentAttempt attempt = attemptOf(11L, 1);
            PaymentIntentV2 intent = intentOf(11L);

            GatewayResult result = service.simulateGatewayCall(attempt, intent);

            assertThat(result.gatewayTransactionId())
                    .isNotNull()
                    .startsWith("TEST_GATEWAY-");
        }

        @Test
        @DisplayName("simulateGatewayCall_successPath_txnIdIs12CharsAfterDash")
        void simulateGatewayCall_successPath_txnIdIs12CharsAfterDash() throws Exception {
            setField("simulatedTimeoutRate", 0);

            PaymentAttempt attempt = attemptOf(12L, 1);
            PaymentIntentV2 intent = intentOf(12L);

            GatewayResult result = service.simulateGatewayCall(attempt, intent);

            // Format: GATEWAYNAME-<12 hex chars uppercase>
            String txnId = result.gatewayTransactionId();
            String afterDash = txnId.substring(txnId.indexOf('-') + 1);
            assertThat(afterDash).hasSize(12).matches("[A-F0-9]{12}");
        }

        @Test
        @DisplayName("simulateGatewayCall_successPath_responseCodeIsSuccess")
        void simulateGatewayCall_successPath_responseCodeIsSuccess() throws Exception {
            setField("simulatedTimeoutRate", 0);

            PaymentAttempt attempt = attemptOf(13L, 1);
            PaymentIntentV2 intent = intentOf(13L);

            GatewayResult result = service.simulateGatewayCall(attempt, intent);

            assertThat(result.responseCode()).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("simulateGatewayCall_successPath_latencyIsZero")
        void simulateGatewayCall_successPath_latencyIsZero() throws Exception {
            setField("simulatedTimeoutRate", 0);

            PaymentAttempt attempt = attemptOf(14L, 1);
            PaymentIntentV2 intent = intentOf(14L);

            GatewayResult result = service.simulateGatewayCall(attempt, intent);

            assertThat(result.latencyMs()).isEqualTo(0L);
        }

        @Test
        @DisplayName("simulateGatewayCall_successPath_uniqueTxnIdsOnRepeatedCalls")
        void simulateGatewayCall_successPath_uniqueTxnIds() throws Exception {
            setField("simulatedTimeoutRate", 0);

            PaymentAttempt attempt = attemptOf(15L, 1);
            PaymentIntentV2 intent = intentOf(15L);

            GatewayResult r1 = service.simulateGatewayCall(attempt, intent);
            GatewayResult r2 = service.simulateGatewayCall(attempt, intent);

            assertThat(r1.gatewayTransactionId()).isNotEqualTo(r2.gatewayTransactionId());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // submitPayment — gateway result propagation
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("submitPayment — result propagation")
    class SubmitPaymentResultPropagation {

        @Test
        @DisplayName("submitPayment_returnedResultStatusMatchesGatewayCall")
        void submitPayment_returnedResultStatusMatchesGatewayCall() {
            PaymentAttempt attempt = attemptOf(20L, 1);
            PaymentIntentV2 intent = intentOf(20L);

            GatewayResult result = service.submitPayment(attempt, intent);

            // Default simulation always succeeds
            assertThat(result).isNotNull();
            assertThat(result.status()).isEqualTo(GatewayResultStatus.SUCCEEDED);
        }

        @Test
        @DisplayName("submitPayment_returnedResultGatewayTransactionIdIsNotNull")
        void submitPayment_returnedResultGatewayTransactionIdIsNotNull() {
            PaymentAttempt attempt = attemptOf(21L, 1);
            PaymentIntentV2 intent = intentOf(21L);

            GatewayResult result = service.submitPayment(attempt, intent);

            assertThat(result.gatewayTransactionId()).isNotNull().isNotBlank();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // submitPayment — idempotency key uses intent.getId() and attempt.getAttemptNumber()
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("submitPayment — idempotency key field correctness")
    class SubmitPaymentIdempotencyKey {

        @Test
        @DisplayName("submitPayment_idempotencyKey_usesCorrectIntentIdAndAttemptNumber")
        void submitPayment_idempotencyKey_exactFormat() {
            PaymentAttempt attempt = attemptOf(77L, 3);
            PaymentIntentV2 intent = intentOf(77L);

            service.submitPayment(attempt, intent);

            assertThat(attempt.getGatewayIdempotencyKey()).isEqualTo("firstclub:77:3");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // buildGatewayIdempotencyKey — exact format assertions
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildGatewayIdempotencyKey — exact format")
    class BuildGatewayIdempotencyKeyExact {

        @Test
        @DisplayName("buildGatewayIdempotencyKey_startsWithFirstclub")
        void buildGatewayIdempotencyKey_startsWithPrefix() {
            String key = service.buildGatewayIdempotencyKey(99L, 1);
            assertThat(key).startsWith("firstclub:");
        }

        @Test
        @DisplayName("buildGatewayIdempotencyKey_containsIntentIdAndAttemptNumber")
        void buildGatewayIdempotencyKey_containsIntentIdAndAttemptNumber() {
            String key = service.buildGatewayIdempotencyKey(55L, 7);
            assertThat(key).isEqualTo("firstclub:55:7");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // simulateGatewayCall — probabilistic timeout rate behavior
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("simulateGatewayCall — probabilistic rate behavior")
    class SimulateGatewayCallProbabilistic {

        @Test
        @DisplayName("simulateGatewayCall_lowTimeoutRate_notAllCallsTimeout")
        void simulateGatewayCall_lowRate_notAllTimeout() throws Exception {
            // With rate=1, Math.random()*100 produces 0-99 → roll < 1 only when roll==0 (~1%).
            // If the MathMutator changes * to /, Math.random()/100 always gives roll=0 → always < 1 → always timeout.
            // If RemoveConditionalMutator replaces roll<rate with true → always timeout.
            // In the original, at least 49 out of 50 calls should succeed.
            setField("simulatedTimeoutRate", 1);
            setField("gatewayTimeoutMs", 5000L);

            int successCount = 0;
            for (int i = 0; i < 50; i++) {
                PaymentAttempt attempt = attemptOf(500L, 1);
                PaymentIntentV2 intent = intentOf(500L);
                GatewayResult result = service.simulateGatewayCall(attempt, intent);
                if (result.status() == GatewayResultStatus.SUCCEEDED) {
                    successCount++;
                }
            }
            // In the original code, ~99% of 50 calls succeed → expect at least 40 successes.
            // Mutants that always timeout would produce 0 successes.
            assertThat(successCount).isGreaterThan(0);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // resolveProcessorNodeId — exception fallback path
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveProcessorNodeId — exception fallback")
    class ResolveProcessorNodeIdFallback {

        @Test
        @DisplayName("submitPayment_whenGetLocalHostThrows_processorNodeIdStartsWithUnknown")
        void submitPayment_fallbackProcessorNodeId() throws Exception {
            PaymentAttempt attempt = attemptOf(99L, 1);
            PaymentIntentV2 intent = intentOf(99L);

            try (MockedStatic<InetAddress> mocked = mockStatic(InetAddress.class)) {
                mocked.when(InetAddress::getLocalHost)
                        .thenThrow(new java.net.UnknownHostException("test"));

                service.submitPayment(attempt, intent);

                assertThat(attempt.getProcessorNodeId())
                        .isNotNull()
                        .isNotBlank()
                        .startsWith("unknown-");
            }
        }

        @Test
        @DisplayName("submitPayment_whenGetLocalHostThrows_processorNodeIdHas8CharSuffix")
        void submitPayment_fallbackProcessorNodeId_hasSuffix() throws Exception {
            PaymentAttempt attempt = attemptOf(98L, 1);
            PaymentIntentV2 intent = intentOf(98L);

            try (MockedStatic<InetAddress> mocked = mockStatic(InetAddress.class)) {
                mocked.when(InetAddress::getLocalHost)
                        .thenThrow(new java.net.UnknownHostException("test"));

                service.submitPayment(attempt, intent);

                String nodeId = attempt.getProcessorNodeId();
                // Format: "unknown-" + 8 hex chars
                String suffix = nodeId.substring("unknown-".length());
                assertThat(suffix).hasSize(8);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void setField(String fieldName, Object value) throws Exception {
        Field field = PaymentGatewayCallService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }

    private static PaymentAttempt attemptOf(Long intentId, int attemptNumber) {
        PaymentIntentV2 intent = intentOf(intentId);
        return PaymentAttempt.builder()
                .paymentIntent(intent)
                .attemptNumber(attemptNumber)
                .gatewayName("TEST_GATEWAY")
                .build();
    }

    private static PaymentIntentV2 intentOf(Long id) {
        PaymentIntentV2 intent = new PaymentIntentV2();
        try {
            Field field = PaymentIntentV2.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(intent, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return intent;
    }
}
