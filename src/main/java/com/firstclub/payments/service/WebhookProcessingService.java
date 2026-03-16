package com.firstclub.payments.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.billing.service.InvoiceService;
import com.firstclub.ledger.dto.LedgerLineRequest;
import com.firstclub.ledger.entity.LedgerEntryType;
import com.firstclub.ledger.entity.LedgerReferenceType;
import com.firstclub.ledger.entity.LineDirection;
import com.firstclub.ledger.service.LedgerService;
import com.firstclub.payments.dto.WebhookPayloadDTO;
import com.firstclub.payments.entity.*;
import com.firstclub.payments.model.PaymentIntentStatus;
import com.firstclub.payments.repository.*;
import com.firstclub.payments.webhooks.WebhookDedupService;
import com.firstclub.platform.dedup.DedupResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Receives and processes webhook events from the (fake) payment gateway.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Idempotency — an event_id that has already been processed is silently
 *       acknowledged as {@link WebhookIngestResult#DUPLICATE}.
 *   <li>Signature verification — rejects events with bad HMACs and flags them
 *       so the retry job leaves them alone.
 *   <li>Persistence — every inbound event is stored before processing begins.
 *   <li>State transitions — updates the PaymentIntent status and creates the
 *       corresponding {@link Payment} row.
 *   <li>Retry bookkeeping — on failure the event is re-scheduled with
 *       exponential backoff; after {@link #MAX_ATTEMPTS} failures of the same
 *       event the payload is also copied to the dead-letter table.
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookProcessingService {

    public static final int MAX_ATTEMPTS = 5;

    private final WebhookEventRepository webhookEventRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentIntentV2Repository paymentIntentV2Repository;
    private final DeadLetterMessageRepository deadLetterRepository;
    private final WebhookSignatureService signatureService;
    private final PaymentIntentService paymentIntentService;
    private final InvoiceService invoiceService;
    private final LedgerService ledgerService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final WebhookDedupService webhookDedupService;

    private Counter paymentSuccessCounter;
    private Counter paymentFailedCounter;
    private Counter webhookProcessedCounter;
    private Counter webhookFailedCounter;

    @PostConstruct
    public void init() {
        paymentSuccessCounter   = meterRegistry.counter("payment_success_total");
        paymentFailedCounter    = meterRegistry.counter("payment_failed_total");
        webhookProcessedCounter = meterRegistry.counter("webhook_processed_total");
        webhookFailedCounter    = meterRegistry.counter("webhook_failed_total");
    }

    // -------------------------------------------------------------------------
    // Public entry-points
    // -------------------------------------------------------------------------

    /**
     * Stores the raw event and—if the signature is valid—processes it
     * immediately.
     */
    @Transactional
    public WebhookIngestResult ingestWebhookEvent(String payload, String signature) {
        try {
            WebhookPayloadDTO dto = objectMapper.readValue(payload, WebhookPayloadDTO.class);

            // ── Phase 6: Redis fast-path dedup (before any DB hit) ────────────────
            String provider = resolveProvider(dto);
            if (dto.getEventId() != null && !dto.getEventId().isBlank()) {
                DedupResult dedupResult = webhookDedupService.checkByEventId(provider, dto.getEventId());
                if (dedupResult == DedupResult.DUPLICATE) {
                    log.debug("[WEBHOOK-DEDUP] Fast-path duplicate for event {}/{}",
                            provider, dto.getEventId());
                    return WebhookIngestResult.DUPLICATE;
                }
            } else {
                // No stable event-id — fall back to payload hash
                String payloadHash = webhookDedupService.computePayloadHash(payload);
                DedupResult hashResult = webhookDedupService.checkByPayloadHash(provider, payloadHash);
                if (hashResult == DedupResult.DUPLICATE) {
                    log.debug("[WEBHOOK-DEDUP] Payload-hash duplicate for provider {}", provider);
                    return WebhookIngestResult.DUPLICATE;
                }
            }

            // Idempotency check (DB authoritative)
            var existing = webhookEventRepository.findByEventId(dto.getEventId());
            if (existing.isPresent() && existing.get().isProcessed()) {
                log.debug("Duplicate webhook event ignored: {}", dto.getEventId());
                return WebhookIngestResult.DUPLICATE;
            }

            boolean sigValid = signatureService.verify(payload, signature);

            WebhookEvent event = existing.orElseGet(() -> {
                WebhookEvent e = WebhookEvent.builder()
                        .provider(provider)
                        .eventId(dto.getEventId())
                        .eventType(dto.getEventType())
                        .payload(payload)
                        .signatureValid(sigValid)
                        .processed(false)
                        .attempts(0)
                        .nextAttemptAt(LocalDateTime.now())
                        .build();
                return webhookEventRepository.save(e);
            });

            if (!sigValid) {
                log.warn("Invalid webhook signature for event {}", dto.getEventId());
                return WebhookIngestResult.INVALID_SIGNATURE;
            }

            processEventInternal(event, dto);

            // Seed Redis after successful processing so future retries are fast-pathed
            webhookDedupService.recordWebhookReceived(provider, dto.getEventId());

            return WebhookIngestResult.PROCESSED;

        } catch (Exception ex) {
            log.error("Unexpected error ingesting webhook", ex);
            return WebhookIngestResult.ERROR;
        }
    }

    /**
     * Called by the retry job for events that were previously stored but not
     * yet successfully processed.
     */
    @Transactional
    public void processStoredEvent(WebhookEvent event) {
        if (event.isProcessed()) {
            return;
        }
        if (!event.isSignatureValid()) {
            log.warn("Refusing to retry webhook event {} — signature was invalid on ingest",
                    event.getEventId());
            return;
        }
        try {
            WebhookPayloadDTO dto = objectMapper.readValue(event.getPayload(), WebhookPayloadDTO.class);
            processEventInternal(event, dto);
        } catch (Exception ex) {
            log.error("Failed to process stored webhook event {}", event.getEventId(), ex);
            recordFailure(event, ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void processEventInternal(WebhookEvent event, WebhookPayloadDTO dto) {
        try {
            String eventType = dto.getEventType();
            Long piId = dto.getPaymentIntentId();

            switch (eventType) {
                case "PAYMENT_INTENT.SUCCEEDED" -> handleSucceeded(piId, dto);
                case "PAYMENT_INTENT.FAILED"    -> handleFailed(piId, dto);
                default -> log.info("Unhandled webhook event type '{}', ignoring.", eventType);
            }

            event.setProcessed(true);
            event.setProcessedAt(LocalDateTime.now());
            event.setAttempts(event.getAttempts() + 1);
            event.setLastError(null);
            webhookEventRepository.save(event);

            webhookProcessedCounter.increment();
            log.info("Webhook event {} ({}) processed successfully", event.getEventId(), eventType);

        } catch (Exception ex) {
            log.error("Error processing webhook event {}", event.getEventId(), ex);
            recordFailure(event, ex.getMessage());
            throw ex;   // re-throw so @Transactional can roll back PI/Payment writes
        }
    }

    private void handleSucceeded(Long piId, WebhookPayloadDTO dto) {
        // Drive the intent through PROCESSING → SUCCEEDED
        PaymentIntent pi = paymentIntentService.findEntityById(piId);
        if (pi.getStatus() != PaymentIntentStatus.PROCESSING) {
            paymentIntentService.markProcessing(piId);
            pi = paymentIntentService.findEntityById(piId);   // refresh
        }
        paymentIntentService.markSucceeded(piId);

        // Create a captured Payment row and post double-entry (idempotent — gateway_txn_id is UNIQUE)
        if (!paymentRepository.existsByGatewayTxnId(dto.getGatewayTxnId())) {
            Long resolvedMerchantId = resolveMerchantId(piId);
            Payment payment = paymentRepository.save(Payment.builder()
                    .paymentIntentId(piId)
                    .merchantId(resolvedMerchantId)
                    .amount(pi.getAmount())
                    .capturedAmount(pi.getAmount())
                    .netAmount(pi.getAmount())
                    .currency(pi.getCurrency())
                    .status(PaymentStatus.CAPTURED)
                    .gatewayTxnId(dto.getGatewayTxnId())
                    .capturedAt(LocalDateTime.now())
                    .build());

            // DR PG_CLEARING / CR SUBSCRIPTION_LIABILITY
            ledgerService.postEntry(
                    LedgerEntryType.PAYMENT_CAPTURED,
                    LedgerReferenceType.PAYMENT,
                    payment.getId(),
                    payment.getCurrency(),
                    List.of(
                            LedgerLineRequest.builder()
                                    .accountName("PG_CLEARING")
                                    .direction(LineDirection.DEBIT)
                                    .amount(payment.getAmount())
                                    .build(),
                            LedgerLineRequest.builder()
                                    .accountName("SUBSCRIPTION_LIABILITY")
                                    .direction(LineDirection.CREDIT)
                                    .amount(payment.getAmount())
                                    .build()
                    )
            );
        }

        // Notify billing — mark invoice PAID and activate subscription
        if (pi.getInvoiceId() != null) {
            try {
                invoiceService.onPaymentSucceeded(pi.getInvoiceId());
            } catch (Exception ex) {
                log.error("Billing post-processing failed for invoice {} (PaymentIntent {})",
                        pi.getInvoiceId(), piId, ex);
                throw ex;  // roll back the whole transaction so the event stays retryable
            }
        }
        paymentSuccessCounter.increment();
    }

    private void handleFailed(Long piId, WebhookPayloadDTO dto) {
        PaymentIntent pi = paymentIntentService.findEntityById(piId);
        if (pi.getStatus() != PaymentIntentStatus.PROCESSING) {
            paymentIntentService.markProcessing(piId);
            pi = paymentIntentService.findEntityById(piId);
        }
        paymentIntentService.markFailed(piId);

        if (!paymentRepository.existsByGatewayTxnId(dto.getGatewayTxnId())) {
            paymentRepository.save(Payment.builder()
                    .paymentIntentId(piId)
                    .amount(pi.getAmount())
                    .currency(pi.getCurrency())
                    .status(PaymentStatus.FAILED)
                    .gatewayTxnId(dto.getGatewayTxnId())
                    .build());
        }
        paymentFailedCounter.increment();
    }

    /**
     * Best-effort merchant_id resolution for V2 payment intents.
     * Returns null for legacy V1 intents that have no V2 counterpart.
     */
    private Long resolveMerchantId(Long piId) {
        try {
            Optional<Long> merchantId = paymentIntentV2Repository.findMerchantIdById(piId);
            return merchantId.orElse(null);
        } catch (Exception ex) {
            log.debug("Could not resolve merchantId for paymentIntentId={}: {}", piId, ex.getMessage());
            return null;
        }
    }

    /**
     * Resolves the gateway provider identifier from the DTO.
     * Falls back to "gateway" to remain compatible with V37-and-earlier events.
     */
    private static String resolveProvider(WebhookPayloadDTO dto) {
        // WebhookPayloadDTO does not carry a provider field yet;
        // a future enhancement can propagate it from the HTTP header or path variable.
        return "gateway";
    }

    private void recordFailure(WebhookEvent event, String errorMessage) {
        int newAttempts = event.getAttempts() + 1;
        long backoffSeconds = Math.min(3600L, (long) Math.pow(2, newAttempts));

        event.setAttempts(newAttempts);
        event.setLastError(errorMessage);
        event.setNextAttemptAt(LocalDateTime.now().plusSeconds(backoffSeconds));
        webhookEventRepository.save(event);

        // Always write a dead-letter record so on-call can inspect failures
        deadLetterRepository.save(DeadLetterMessage.builder()
                .source("WEBHOOK")
                .payload(event.getPayload())
                .error(errorMessage != null ? errorMessage : "unknown error")
                .build());

        webhookFailedCounter.increment();
        log.warn("Webhook event {} failed (attempt {}), next retry in {}s",
                event.getEventId(), newAttempts, backoffSeconds);
    }
}
