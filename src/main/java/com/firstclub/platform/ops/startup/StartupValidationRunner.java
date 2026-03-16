package com.firstclub.platform.ops.startup;

import com.firstclub.ledger.repository.LedgerAccountRepository;
import com.firstclub.membership.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Startup checks that validate critical configuration before the application
 * begins serving traffic.
 *
 * <h3>Fail-fast checks (non-dev/non-test profiles)</h3>
 * <ul>
 *   <li>Webhook HMAC secret must not be the insecure dev placeholder.</li>
 *   <li>PII encryption key ({@code PII_ENC_KEY} env var) must be set.</li>
 *   <li>JWT secret must not be the insecure dev-only default.</li>
 *   <li>Gateway emulator must not be enabled (fake payment processor).</li>
 * </ul>
 *
 * <h3>Advisory checks (all profiles)</h3>
 * <ul>
 *   <li>Chart of accounts has been seeded (at least one LedgerAccount exists).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupValidationRunner implements ApplicationRunner {

    private static final String DEFAULT_WEBHOOK_SECRET = "dev-only-webhook-secret-change-in-prod";

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    @Value("${spring.application.name:membership}")
    private String applicationName;

    @Value("${app.version:unknown}")
    private String appVersion;

    @Value("${payments.webhook.secret:}")
    private String webhookSecret;

    @Value("${app.jwt.secret:}")
    private String jwtSecret;

    @Value("${app.gateway-emulator.enabled:false}")
    private boolean gatewayEmulatorEnabled;

    private final LedgerAccountRepository ledgerAccountRepository;

    @Override
    public void run(ApplicationArguments args) {
        log.info("[STARTUP] app={} version={} profile={}", applicationName, appVersion, activeProfile);
        boolean isNonDevProfile = !activeProfile.contains("dev") && !activeProfile.contains("test");
        validateWebhookSecret(isNonDevProfile);
        validateJwtSecret(isNonDevProfile);
        validateGatewayEmulator(isNonDevProfile);
        validatePiiEncKey(isNonDevProfile);
        validateChartOfAccounts();
    }

    private void validateWebhookSecret(boolean failFast) {
        boolean isDefault = DEFAULT_WEBHOOK_SECRET.equals(webhookSecret);
        if (isDefault) {
            if (failFast) {
                throw new IllegalStateException(
                        "[STARTUP] SECURITY: payments.webhook.secret is the insecure dev placeholder. " +
                        "Set the WEBHOOK_SECRET environment variable before deploying.");
            } else {
                log.warn("[STARTUP] payments.webhook.secret is the default dev value — change before production.");
            }
        }
    }

    private void validatePiiEncKey(boolean failFast) {
        String piiKey = System.getenv("PII_ENC_KEY");
        if (piiKey == null || piiKey.isBlank()) {
            if (failFast) {
                throw new IllegalStateException(
                        "[STARTUP] SECURITY: PII_ENC_KEY environment variable is not set. " +
                        "A 256-bit AES key is required for PII encryption. " +
                        "Generate with: openssl rand -base64 32");
            } else {
                log.warn("[STARTUP] PII_ENC_KEY is not set — using insecure dev fallback key. " +
                         "Set PII_ENC_KEY before production.");
            }
        }
    }

    private void validateChartOfAccounts() {
        long accountCount = ledgerAccountRepository.count();
        if (accountCount == 0) {
            log.warn("[STARTUP] No LedgerAccounts found. Run the AccountSeeder or apply the V8 migration " +
                     "with the default chart of accounts before going live.");
        } else {
            log.info("[STARTUP] Chart of accounts present ({} accounts).", accountCount);
        }
    }

    private void validateJwtSecret(boolean failFast) {
        if (JwtTokenProvider.DEV_FALLBACK_SECRET.equals(jwtSecret)) {
            if (failFast) {
                throw new IllegalStateException(
                        "[STARTUP] SECURITY: app.jwt.secret is the insecure dev-only default. " +
                        "Set the JWT_SECRET environment variable to a securely generated value " +
                        "before deploying. Generate with: openssl rand -base64 32");
            } else {
                log.warn("[STARTUP] app.jwt.secret is using the dev-only default — " +
                         "set JWT_SECRET before production.");
            }
        }
    }

    private void validateGatewayEmulator(boolean failFast) {
        if (gatewayEmulatorEnabled) {
            if (failFast) {
                throw new IllegalStateException(
                        "[STARTUP] SECURITY: app.gateway-emulator.enabled=true in a non-dev/non-test profile. " +
                        "The fake payment gateway emulator must not be active in staging or production.");
            } else {
                log.warn("[STARTUP] Gateway emulator is enabled — " +
                         "set app.gateway-emulator.enabled=false before production.");
            }
        }
    }
}
