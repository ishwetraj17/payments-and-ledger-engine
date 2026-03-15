package com.firstclub.payments.gateway;

import com.firstclub.payments.entity.FailureCategory;
import com.firstclub.payments.entity.PaymentAttempt;
import com.firstclub.payments.entity.PaymentIntentV2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Mutation-killing tests for {@link GatewayStatusResolver}.
 *
 * <p>Targets all 13 no-coverage mutants identified by PIT baseline:
 * <ul>
 *   <li>resolveStatus line 50 — 2 mutants on paymentIntent null guard (log ternary)</li>
 *   <li>resolveStatus line 57 — 1 NullReturnVals mutant on return</li>
 *   <li>queryGatewayStatus line 70 — 2 mutants on idempotency key null check</li>
 *   <li>queryGatewayStatus line 73 — 1 NullReturnVals on unknown return</li>
 *   <li>queryGatewayStatus line 76 — 3 mutants on attemptNumber boundary</li>
 *   <li>queryGatewayStatus line 77 — 2 mutants on gatewayName null check</li>
 *   <li>queryGatewayStatus line 81 — 1 NullReturnVals on succeeded return</li>
 *   <li>queryGatewayStatus line 83 — 1 NullReturnVals on failed return</li>
 * </ul>
 */
@DisplayName("GatewayStatusResolver — Mutation Tests")
class GatewayStatusResolverMutationTest {

    private GatewayStatusResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new GatewayStatusResolver();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // resolveStatus — delegates to queryGatewayStatus and returns result
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveStatus — return value propagation")
    class ResolveStatusReturnValue {

        @Test
        @DisplayName("resolveStatus returns non-null result for valid attempt")
        void resolveStatus_validAttempt_returnsNonNull() {
            // Kills: resolveStatus line 57 NullReturnVals
            PaymentAttempt attempt = attemptWithKey(1L, 1, "TEST_GW", "key-1");

            GatewayResult result = resolver.resolveStatus(attempt);

            assertThat(result).isNotNull();
            assertThat(result.status()).isEqualTo(GatewayResultStatus.SUCCEEDED);
        }

        @Test
        @DisplayName("resolveStatus with null paymentIntent does not throw")
        void resolveStatus_nullPaymentIntent_doesNotThrow() {
            // Kills: resolveStatus line 50 RemoveConditional_EQUAL_IF
            //   (mutant replaces != null with true → NPE on null paymentIntent)
            PaymentAttempt attempt = PaymentAttempt.builder()
                    .attemptNumber(1)
                    .gatewayName("TEST_GW")
                    .gatewayIdempotencyKey("key-null-intent")
                    .build();
            // paymentIntent is null — should not throw

            assertThatCode(() -> resolver.resolveStatus(attempt)).doesNotThrowAnyException();
            GatewayResult result = resolver.resolveStatus(attempt);
            assertThat(result).isNotNull();
            assertThat(result.status()).isEqualTo(GatewayResultStatus.SUCCEEDED);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // queryGatewayStatus — null idempotency key → UNKNOWN
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("queryGatewayStatus — null idempotency key")
    class NullIdempotencyKey {

        @Test
        @DisplayName("null gatewayIdempotencyKey returns UNKNOWN status")
        void nullKey_returnsUnknown() {
            // Kills: line 70 RemoveConditional_EQUAL_ELSE, EQUAL_IF
            // Kills: line 73 NullReturnVals
            PaymentAttempt attempt = attemptWithKey(1L, 1, "TEST_GW", null);

            GatewayResult result = resolver.resolveStatus(attempt);

            assertThat(result).isNotNull();
            assertThat(result.status()).isEqualTo(GatewayResultStatus.UNKNOWN);
        }

        @Test
        @DisplayName("null gatewayIdempotencyKey returns null gatewayTransactionId")
        void nullKey_returnsNullTxnId() {
            PaymentAttempt attempt = attemptWithKey(2L, 1, "TEST_GW", null);

            GatewayResult result = resolver.resolveStatus(attempt);

            assertThat(result.gatewayTransactionId()).isNull();
        }

        @Test
        @DisplayName("null gatewayIdempotencyKey returns null responseCode")
        void nullKey_returnsNullResponseCode() {
            PaymentAttempt attempt = attemptWithKey(3L, 1, "TEST_GW", null);

            GatewayResult result = resolver.resolveStatus(attempt);

            assertThat(result.responseCode()).isNull();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // queryGatewayStatus — attemptNumber <= 5 → SUCCEEDED
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("queryGatewayStatus — success path (attemptNumber <= 5)")
    class SuccessPath {

        @Test
        @DisplayName("attemptNumber 1 returns SUCCEEDED")
        void attemptNumber1_returnsSucceeded() {
            // Kills: line 76 RemoveConditional_ORDER_ELSE
            // Kills: line 81 NullReturnVals
            PaymentAttempt attempt = attemptWithKey(10L, 1, "STRIPE", "key-10");

            GatewayResult result = resolver.resolveStatus(attempt);

            assertThat(result).isNotNull();
            assertThat(result.status()).isEqualTo(GatewayResultStatus.SUCCEEDED);
        }

        @Test
        @DisplayName("attemptNumber 5 (boundary) returns SUCCEEDED")
        void attemptNumber5_boundary_returnsSucceeded() {
            // Kills: line 76 ConditionalsBoundaryMutator (<= to <)
            PaymentAttempt attempt = attemptWithKey(11L, 5, "STRIPE", "key-11");

            GatewayResult result = resolver.resolveStatus(attempt);

            assertThat(result.status()).isEqualTo(GatewayResultStatus.SUCCEEDED);
        }

        @Test
        @DisplayName("success path responseCode is RECONCILED_SUCCESS")
        void successPath_responseCode() {
            PaymentAttempt attempt = attemptWithKey(12L, 3, "ADYEN", "key-12");

            GatewayResult result = resolver.resolveStatus(attempt);

            assertThat(result.responseCode()).isEqualTo("RECONCILED_SUCCESS");
        }

        @Test
        @DisplayName("success path gatewayTransactionId starts with uppercase gateway name")
        void successPath_txnIdStartsWithGatewayName() {
            // Kills: line 77 RemoveConditional_EQUAL_IF (gatewayName null → true)
            PaymentAttempt attempt = attemptWithKey(13L, 2, "stripe", "key-13");

            GatewayResult result = resolver.resolveStatus(attempt);

            assertThat(result.gatewayTransactionId())
                    .isNotNull()
                    .startsWith("STRIPE-RECONCILED-");
        }

        @Test
        @DisplayName("success path with null gatewayName uses GW prefix")
        void successPath_nullGatewayName_usesGWPrefix() {
            // Kills: line 77 RemoveConditional_EQUAL_ELSE (gatewayName null → false)
            PaymentAttempt attempt = attemptWithKey(14L, 1, null, "key-14");

            GatewayResult result = resolver.resolveStatus(attempt);

            assertThat(result.gatewayTransactionId())
                    .isNotNull()
                    .startsWith("GW-RECONCILED-");
        }

        @Test
        @DisplayName("success path gatewayTransactionId has 8-char hex suffix")
        void successPath_txnIdHas8CharHexSuffix() {
            PaymentAttempt attempt = attemptWithKey(15L, 1, "BRAINTREE", "key-15");

            GatewayResult result = resolver.resolveStatus(attempt);

            String txnId = result.gatewayTransactionId();
            String suffix = txnId.substring(txnId.lastIndexOf("-") + 1);
            assertThat(suffix).hasSize(8).matches("[A-F0-9]{8}");
        }

        @Test
        @DisplayName("success path latencyMs is null")
        void successPath_latencyIsNull() {
            PaymentAttempt attempt = attemptWithKey(16L, 1, "GW", "key-16");

            GatewayResult result = resolver.resolveStatus(attempt);

            assertThat(result.latencyMs()).isNull();
        }

        @Test
        @DisplayName("success path failureCategory is null")
        void successPath_failureCategoryIsNull() {
            PaymentAttempt attempt = attemptWithKey(17L, 1, "GW", "key-17");

            GatewayResult result = resolver.resolveStatus(attempt);

            assertThat(result.failureCategory()).isNull();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // queryGatewayStatus — attemptNumber > 5 → FAILED
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("queryGatewayStatus — failure path (attemptNumber > 5)")
    class FailurePath {

        @Test
        @DisplayName("attemptNumber 6 returns FAILED")
        void attemptNumber6_returnsFailed() {
            // Kills: line 76 RemoveConditional_ORDER_IF
            // Kills: line 83 NullReturnVals
            PaymentAttempt attempt = attemptWithKey(20L, 6, "STRIPE", "key-20");

            GatewayResult result = resolver.resolveStatus(attempt);

            assertThat(result).isNotNull();
            assertThat(result.status()).isEqualTo(GatewayResultStatus.FAILED);
        }

        @Test
        @DisplayName("attemptNumber 10 returns FAILED")
        void attemptNumber10_returnsFailed() {
            PaymentAttempt attempt = attemptWithKey(21L, 10, "STRIPE", "key-21");

            GatewayResult result = resolver.resolveStatus(attempt);

            assertThat(result.status()).isEqualTo(GatewayResultStatus.FAILED);
        }

        @Test
        @DisplayName("failure path failureCategory is GATEWAY_ERROR")
        void failurePath_failureCategory() {
            PaymentAttempt attempt = attemptWithKey(22L, 6, "STRIPE", "key-22");

            GatewayResult result = resolver.resolveStatus(attempt);

            assertThat(result.failureCategory()).isEqualTo(FailureCategory.GATEWAY_ERROR);
        }

        @Test
        @DisplayName("failure path responseCode is GATEWAY_NOT_PROCESSED")
        void failurePath_responseCode() {
            PaymentAttempt attempt = attemptWithKey(23L, 7, "STRIPE", "key-23");

            GatewayResult result = resolver.resolveStatus(attempt);

            assertThat(result.responseCode()).isEqualTo("GATEWAY_NOT_PROCESSED");
        }

        @Test
        @DisplayName("failure path responseMessage is exact message")
        void failurePath_responseMessage() {
            PaymentAttempt attempt = attemptWithKey(24L, 8, "STRIPE", "key-24");

            GatewayResult result = resolver.resolveStatus(attempt);

            assertThat(result.responseMessage()).isEqualTo("Gateway confirmed payment was not processed");
        }

        @Test
        @DisplayName("failure path gatewayTransactionId is null")
        void failurePath_noTxnId() {
            PaymentAttempt attempt = attemptWithKey(25L, 6, "STRIPE", "key-25");

            GatewayResult result = resolver.resolveStatus(attempt);

            assertThat(result.gatewayTransactionId()).isNull();
        }

        @Test
        @DisplayName("failure path latencyMs is null")
        void failurePath_latencyIsNull() {
            PaymentAttempt attempt = attemptWithKey(26L, 6, "STRIPE", "key-26");

            GatewayResult result = resolver.resolveStatus(attempt);

            assertThat(result.latencyMs()).isNull();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static PaymentAttempt attemptWithKey(Long intentId, int attemptNumber,
                                                 String gatewayName, String idempotencyKey) {
        PaymentIntentV2 intent = intentOf(intentId);
        return PaymentAttempt.builder()
                .paymentIntent(intent)
                .attemptNumber(attemptNumber)
                .gatewayName(gatewayName)
                .gatewayIdempotencyKey(idempotencyKey)
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
