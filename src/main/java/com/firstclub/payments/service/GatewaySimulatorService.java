package com.firstclub.payments.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.payments.dto.WebhookPayloadDTO;
import com.firstclub.payments.entity.PaymentIntent;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Fake gateway that simulates an external payment processor sending signed
 * webhook callbacks after a short randomised delay (2-5 seconds).
 *
 * <p>In a real system the gateway would POST to a public URL.  Here the
 * emulator calls {@link WebhookProcessingService#ingestWebhookEvent} directly,
 * which still exercises full HMAC verification and the entire processing
 * pipeline.
 *
 * <p>Disabled by default; enabled only when {@code app.gateway-emulator.enabled=true}
 * (set in application-dev.properties — never in production).
 */
@ConditionalOnProperty(name = "app.gateway-emulator.enabled", havingValue = "true", matchIfMissing = false)
@Service
@RequiredArgsConstructor
@Slf4j
public class GatewaySimulatorService {

    private static final int MIN_DELAY_SECS = 2;
    private static final int MAX_DELAY_SECS = 6; // exclusive for nextInt

    private final WebhookProcessingService webhookProcessingService;
    private final WebhookSignatureService signatureService;
    private final ObjectMapper objectMapper;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(4);

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Schedules a signed webhook delivery for the given PaymentIntent.
     *
     * @param pi        the PaymentIntent that was charged
     * @param eventType e.g. "PAYMENT_INTENT.SUCCEEDED" or "PAYMENT_INTENT.FAILED"
     */
    public void scheduleWebhookDispatch(PaymentIntent pi, String eventType) {
        int delaySecs = ThreadLocalRandom.current().nextInt(MIN_DELAY_SECS, MAX_DELAY_SECS);
        log.debug("Scheduling {} webhook for PI {} in {}s", eventType, pi.getId(), delaySecs);
        scheduler.schedule(() -> dispatch(pi, eventType), delaySecs, TimeUnit.SECONDS);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void dispatch(PaymentIntent pi, String eventType) {
        try {
            String gatewayTxnId = "gwtxn_" + compact(UUID.randomUUID()).substring(0, 20);

            WebhookPayloadDTO dto = WebhookPayloadDTO.builder()
                    .eventId("evt_" + compact(UUID.randomUUID()).substring(0, 20))
                    .eventType(eventType)
                    .paymentIntentId(pi.getId())
                    .amount(pi.getAmount())
                    .currency(pi.getCurrency())
                    .gatewayTxnId(gatewayTxnId)
                    .timestamp(LocalDateTime.now())
                    .build();

            String payloadJson = objectMapper.writeValueAsString(dto);
            String signature   = signatureService.sign(payloadJson);

            WebhookIngestResult result = webhookProcessingService.ingestWebhookEvent(payloadJson, signature);
            log.info("Gateway dispatched {} for PI {} → {}", eventType, pi.getId(), result);

        } catch (Exception ex) {
            log.error("Gateway failed to dispatch {} for PI {}", eventType, pi.getId(), ex);
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static String compact(UUID uuid) {
        return uuid.toString().replace("-", "");
    }
}
