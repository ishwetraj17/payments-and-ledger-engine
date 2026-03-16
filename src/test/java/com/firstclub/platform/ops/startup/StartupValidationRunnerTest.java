package com.firstclub.platform.ops.startup;

import com.firstclub.ledger.repository.LedgerAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StartupValidationRunner — Unit Tests")
class StartupValidationRunnerTest {

    private static final String DEV_WEBHOOK_SECRET = "dev-only-webhook-secret-change-in-prod";
    private static final String SAFE_WEBHOOK_SECRET = "super-secret-random-value-for-tests";

    @Mock
    LedgerAccountRepository ledgerAccountRepository;

    @InjectMocks
    StartupValidationRunner runner;

    private final ApplicationArguments noArgs = new DefaultApplicationArguments();

    @Nested
    @DisplayName("Dev profile — advisory warnings only, no fail-fast")
    class DevProfile {

        @BeforeEach
        void useDevProfile() {
            ReflectionTestUtils.setField(runner, "activeProfile", "dev");
            when(ledgerAccountRepository.count()).thenReturn(1L);
        }

        @Test
        @DisplayName("Default webhook secret does not throw in dev")
        void defaultWebhookSecret_devProfile_doesNotThrow() {
            ReflectionTestUtils.setField(runner, "webhookSecret", DEV_WEBHOOK_SECRET);
            assertThatNoException().isThrownBy(() -> runner.run(noArgs));
        }

        @Test
        @DisplayName("Missing PII_ENC_KEY does not throw in dev (key is unset in CI by default)")
        void missingPiiEncKey_devProfile_doesNotThrow() {
            // PII_ENC_KEY is not set in the test environment; dev profile must not fail-fast
            ReflectionTestUtils.setField(runner, "webhookSecret", SAFE_WEBHOOK_SECRET);
            assertThatNoException().isThrownBy(() -> runner.run(noArgs));
        }
    }

    @Nested
    @DisplayName("Prod profile — fail-fast on missing/insecure config")
    class ProdProfile {

        @BeforeEach
        void useProdProfile() {
            ReflectionTestUtils.setField(runner, "activeProfile", "prod");
        }

        @Test
        @DisplayName("Default webhook secret throws in prod")
        void defaultWebhookSecret_prodProfile_throws() {
            ReflectionTestUtils.setField(runner, "webhookSecret", DEV_WEBHOOK_SECRET);
            assertThatThrownBy(() -> runner.run(noArgs))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("WEBHOOK_SECRET");
        }

        @Test
        @DisplayName("Missing PII_ENC_KEY throws in prod when env var is absent")
        void missingPiiEncKey_prodProfile_throws() {
            // PII_ENC_KEY is not set in CI — confirm fail-fast fires in prod profile
            ReflectionTestUtils.setField(runner, "webhookSecret", SAFE_WEBHOOK_SECRET);
            // Only assert if PII_ENC_KEY is actually unset (guards against accidental env var in CI)
            if (System.getenv("PII_ENC_KEY") == null || System.getenv("PII_ENC_KEY").isBlank()) {
                assertThatThrownBy(() -> runner.run(noArgs))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("PII_ENC_KEY");
            }
        }
    }

    @Nested
    @DisplayName("Test profile — advisory warnings only, no fail-fast")
    class TestProfile {

        @BeforeEach
        void useTestProfile() {
            ReflectionTestUtils.setField(runner, "activeProfile", "test");
            when(ledgerAccountRepository.count()).thenReturn(1L);
        }

        @Test
        @DisplayName("Default webhook secret does not throw in test profile")
        void defaultWebhookSecret_testProfile_doesNotThrow() {
            ReflectionTestUtils.setField(runner, "webhookSecret", DEV_WEBHOOK_SECRET);
            assertThatNoException().isThrownBy(() -> runner.run(noArgs));
        }
    }
}
