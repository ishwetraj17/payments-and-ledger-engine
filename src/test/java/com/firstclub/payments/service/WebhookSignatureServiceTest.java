package com.firstclub.payments.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WebhookSignatureService}.
 *
 * The HMAC secret is injected via {@link ReflectionTestUtils} so no Spring
 * context startup is required — these run in milliseconds.
 */
class WebhookSignatureServiceTest {

    private static final String SECRET  = "test-webhook-secret-unit";
    private static final String PAYLOAD = "{\"eventId\":\"evt_abc123\",\"eventType\":\"PAYMENT_INTENT.SUCCEEDED\"}";

    private WebhookSignatureService service;

    @BeforeEach
    void setUp() {
        service = new WebhookSignatureService();
        ReflectionTestUtils.setField(service, "secret", SECRET);
    }

    // -------------------------------------------------------------------------

    @Test
    void sign_producesConsistentHmac() {
        String first  = service.sign(PAYLOAD);
        String second = service.sign(PAYLOAD);

        assertThat(first)
                .as("Same payload must produce the same HMAC")
                .isEqualTo(second);

        // Sanity-check: hex string of 32 bytes → 64 characters
        assertThat(first).hasSize(64);
    }

    @Test
    void verify_returnsTrue_forValidSignature() {
        String sig = service.sign(PAYLOAD);
        assertThat(service.verify(PAYLOAD, sig)).isTrue();
    }

    @Test
    void verify_returnsFalse_forTamperedPayload() {
        String sig = service.sign(PAYLOAD);
        String tampered = PAYLOAD.replace("SUCCEEDED", "FAILED");
        assertThat(service.verify(tampered, sig)).isFalse();
    }

    @Test
    void verify_returnsFalse_forWrongSignature() {
        assertThat(service.verify(PAYLOAD, "0".repeat(64))).isFalse();
    }

    @Test
    void verify_returnsFalse_forNullInputs() {
        assertThat(service.verify(null, "anything")).isFalse();
        assertThat(service.verify(PAYLOAD, null)).isFalse();
    }

    @Test
    void verify_acceptsUpperCaseSignature() {
        // verify() must accept upper-case hex from gateways that use uppercase
        String sig = service.sign(PAYLOAD).toUpperCase();
        assertThat(service.verify(PAYLOAD, sig)).isTrue();
    }

    @Test
    void verify_returnsFalse_forOddLengthSignature() {
        // Odd-length hex is always invalid — should not throw
        assertThat(service.verify(PAYLOAD, "abc")).isFalse();
    }

    @Test
    void verify_returnsFalse_forNonHexSignature() {
        // Non-hex characters should be rejected cleanly
        assertThat(service.verify(PAYLOAD, "z".repeat(64))).isFalse();
    }
}
