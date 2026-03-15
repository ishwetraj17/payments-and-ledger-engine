package com.firstclub.dunning.classification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mutation-killing tests for {@link FailureCodeClassifier}.
 *
 * <p>Covers every CODE_MAP entry, normalisation edge cases, and fallback
 * behaviour to ensure full mutation coverage of the {@code classify} method.
 *
 * <h3>Equivalent mutant (line 80, RemoveConditionalMutator_EQUAL_ELSE index 7)</h3>
 * <p>Mutation: {@code isBlank()} replaced with {@code false} in
 * {@code if (failureCode == null || failureCode.isBlank())}.
 * <p>This is equivalent because blank strings (e.g. {@code "   "}, {@code ""})
 * pass through normalisation ({@code trim().toLowerCase().replace('-','_')}) to
 * produce {@code ""}, which is not a key in CODE_MAP, so the fallback returns
 * {@link FailureCategory#UNKNOWN} — the same result as the short-circuit guard.
 * No test can distinguish the mutated behaviour from the original.
 */
@DisplayName("FailureCodeClassifier — mutation tests")
class FailureCodeClassifierMutationTest {

    private final FailureCodeClassifier classifier = new FailureCodeClassifier();

    // =========================================================================
    // Complete CODE_MAP coverage — every entry asserted exactly
    // =========================================================================

    @Nested
    @DisplayName("All CODE_MAP entries")
    class AllCodeMapEntries {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
            // ── Insufficient funds ────────────────────────────────────────
            "insufficient_funds,              INSUFFICIENT_FUNDS",
            "withdrawal_count_limit_exceeded, INSUFFICIENT_FUNDS",

            // ── Generic decline ───────────────────────────────────────────
            "card_declined,                   CARD_DECLINED_GENERIC",
            "generic_decline,                 CARD_DECLINED_GENERIC",
            "do_not_try_again,                CARD_DECLINED_GENERIC",
            "restricted_card,                 CARD_DECLINED_GENERIC",
            "gateway_declined,                CARD_DECLINED_GENERIC",
            "simulated_decline,               CARD_DECLINED_GENERIC",

            // ── Gateway timeout / processing error ────────────────────────
            "gateway_timeout,                 GATEWAY_TIMEOUT",
            "processing_error,                GATEWAY_TIMEOUT",
            "reenter_transaction,             GATEWAY_TIMEOUT",

            // ── Card expired ──────────────────────────────────────────────
            "expired_card,                    CARD_EXPIRED",
            "invalid_expiry_month,            CARD_EXPIRED",
            "invalid_expiry_year,             CARD_EXPIRED",

            // ── Card not supported ────────────────────────────────────────
            "card_not_supported,              CARD_NOT_SUPPORTED",
            "currency_not_supported,          CARD_NOT_SUPPORTED",
            "transaction_not_allowed,         CARD_NOT_SUPPORTED",

            // ── Issuer not available ──────────────────────────────────────
            "issuer_not_available,            ISSUER_NOT_AVAILABLE",
            "card_issuer_contact_bank,        ISSUER_NOT_AVAILABLE",
            "call_issuer,                     ISSUER_NOT_AVAILABLE",

            // ── Card stolen ───────────────────────────────────────────────
            "stolen_card,                     CARD_STOLEN",

            // ── Card lost ─────────────────────────────────────────────────
            "lost_card,                       CARD_LOST",

            // ── Fraudulent ────────────────────────────────────────────────
            "fraudulent,                      FRAUDULENT",

            // ── Do not honour ─────────────────────────────────────────────
            "do_not_honor,                    DO_NOT_HONOR",
            "no_action_taken,                 DO_NOT_HONOR",

            // ── Invalid account ───────────────────────────────────────────
            "invalid_account,                 INVALID_ACCOUNT",
            "account_blacklisted,             INVALID_ACCOUNT",
        })
        @DisplayName("exact code → exact category")
        void everyEntry(String code, FailureCategory expected) {
            assertThat(classifier.classify(code)).isEqualTo(expected);
        }
    }

    // =========================================================================
    // Normalisation: case-insensitivity
    // =========================================================================

    @Nested
    @DisplayName("Case normalisation")
    class CaseNormalisation {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
            "INSUFFICIENT_FUNDS,   INSUFFICIENT_FUNDS",
            "Expired_Card,         CARD_EXPIRED",
            "STOLEN_CARD,          CARD_STOLEN",
            "Gateway_Timeout,      GATEWAY_TIMEOUT",
            "FRAUDULENT,           FRAUDULENT",
        })
        @DisplayName("upper/mixed case resolves to correct category")
        void mixedCaseLookup(String code, FailureCategory expected) {
            assertThat(classifier.classify(code)).isEqualTo(expected);
        }
    }

    // =========================================================================
    // Normalisation: hyphen → underscore
    // =========================================================================

    @Nested
    @DisplayName("Hyphen normalisation")
    class HyphenNormalisation {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
            "insufficient-funds,   INSUFFICIENT_FUNDS",
            "do-not-honor,         DO_NOT_HONOR",
            "expired-card,         CARD_EXPIRED",
            "gateway-timeout,      GATEWAY_TIMEOUT",
            "card-not-supported,   CARD_NOT_SUPPORTED",
        })
        @DisplayName("hyphens are replaced with underscores before lookup")
        void hyphenatedCodeLookup(String code, FailureCategory expected) {
            assertThat(classifier.classify(code)).isEqualTo(expected);
        }
    }

    // =========================================================================
    // Normalisation: leading/trailing whitespace trimmed
    // =========================================================================

    @Nested
    @DisplayName("Whitespace trimming")
    class WhitespaceTrimming {

        @Test
        @DisplayName("leading spaces trimmed before lookup")
        void leadingSpaces() {
            assertThat(classifier.classify("  stolen_card")).isEqualTo(FailureCategory.CARD_STOLEN);
        }

        @Test
        @DisplayName("trailing spaces trimmed before lookup")
        void trailingSpaces() {
            assertThat(classifier.classify("lost_card  ")).isEqualTo(FailureCategory.CARD_LOST);
        }

        @Test
        @DisplayName("both leading and trailing spaces trimmed")
        void surroundingSpaces() {
            assertThat(classifier.classify("  fraudulent  ")).isEqualTo(FailureCategory.FRAUDULENT);
        }
    }

    // =========================================================================
    // Fallback / UNKNOWN handling
    // =========================================================================

    @Nested
    @DisplayName("Fallback to UNKNOWN")
    class FallbackToUnknown {

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("null and empty string return UNKNOWN")
        void nullAndEmpty(String code) {
            assertThat(classifier.classify(code)).isEqualTo(FailureCategory.UNKNOWN);
        }

        @ParameterizedTest
        @ValueSource(strings = {"   ", "\t", "\n", " \t\n "})
        @DisplayName("blank strings (whitespace-only) return UNKNOWN")
        void blankStrings(String code) {
            assertThat(classifier.classify(code)).isEqualTo(FailureCategory.UNKNOWN);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "totally_unknown_code",
            "some_random_gateway_xyz",
            "not_a_real_code",
            "12345",
        })
        @DisplayName("unmapped codes return UNKNOWN")
        void unmappedCodes(String code) {
            assertThat(classifier.classify(code)).isEqualTo(FailureCategory.UNKNOWN);
        }
    }

    // =========================================================================
    // Contract: classify never returns null
    // =========================================================================

    @Nested
    @DisplayName("Return value contract")
    class ReturnValueContract {

        @Test
        @DisplayName("classify never returns null for known code")
        void knownCodeNeverNull() {
            assertThat(classifier.classify("card_declined")).isNotNull();
        }

        @Test
        @DisplayName("classify never returns null for unknown code")
        void unknownCodeNeverNull() {
            assertThat(classifier.classify("xyz_unknown")).isNotNull();
        }

        @Test
        @DisplayName("classify never returns null for null input")
        void nullInputNeverNull() {
            assertThat(classifier.classify(null)).isNotNull();
        }
    }

    // =========================================================================
    // Combined normalisation: upper-case + hyphens + whitespace
    // =========================================================================

    @Nested
    @DisplayName("Combined normalisation")
    class CombinedNormalisation {

        @Test
        @DisplayName("upper-case + hyphens + whitespace all normalised together")
        void allNormalisationsAtOnce() {
            assertThat(classifier.classify("  DO-NOT-HONOR  "))
                    .isEqualTo(FailureCategory.DO_NOT_HONOR);
        }

        @Test
        @DisplayName("mixed case with hyphens")
        void mixedCaseWithHyphens() {
            assertThat(classifier.classify("Insufficient-Funds"))
                    .isEqualTo(FailureCategory.INSUFFICIENT_FUNDS);
        }
    }
}
