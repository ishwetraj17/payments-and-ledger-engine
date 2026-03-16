package com.firstclub.payments.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.billing.service.InvoiceService;
import com.firstclub.ledger.service.LedgerService;
import com.firstclub.payments.dto.WebhookPayloadDTO;
import com.firstclub.payments.entity.WebhookEvent;
import com.firstclub.payments.service.WebhookIngestResult;
import com.firstclub.payments.model.PaymentIntentStatus;
import com.firstclub.payments.repository.*;
import com.firstclub.payments.webhooks.WebhookDedupService;
import com.firstclub.platform.dedup.DedupResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the dedup integration in {@link WebhookProcessingService}.
 *
 * Covers: Redis fast-path dedup for known event-id, DB-processed check for
 * known event-id, new event processed exactly once, payload-hash dedup for
 * events without stable event-id.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebhookProcessingService — Deduplication Integration Tests")
class WebhookProcessingServiceDedupTest {

    @Mock private WebhookEventRepository         webhookEventRepository;
    @Mock private PaymentRepository              paymentRepository;
    @Mock private PaymentIntentV2Repository      paymentIntentV2Repository;
    @Mock private DeadLetterMessageRepository    deadLetterRepository;
    @Mock private WebhookSignatureService        signatureService;
    @Mock private PaymentIntentService           paymentIntentService;
    @Mock private InvoiceService                 invoiceService;
    @Mock private LedgerService                  ledgerService;
    @Mock private WebhookDedupService            webhookDedupService;

    private final ObjectMapper      objectMapper = new ObjectMapper()
            .findAndRegisterModules();
    private final MeterRegistry     meterRegistry = new SimpleMeterRegistry();

    private WebhookProcessingService service;

    private static final String EVENT_ID   = "evt_unit_001";
    private static final String PAYLOAD    = """
            {"eventId":"evt_unit_001","eventType":"PAYMENT_INTENT.SUCCEEDED",
             "paymentIntentId":1,"gatewayTxnId":"txn_001","timestamp":"2024-01-01T00:00:00"}
            """;

    @BeforeEach
    void setUp() {
        service = new WebhookProcessingService(
                webhookEventRepository, paymentRepository, paymentIntentV2Repository,
                deadLetterRepository, signatureService, paymentIntentService,
                invoiceService, ledgerService, objectMapper, meterRegistry,
                webhookDedupService);
        service.init();
    }

    // ── Redis fast-path duplicate ─────────────────────────────────────────

    @Test
    @DisplayName("returns DUPLICATE immediately when WebhookDedupService flags event-id as known")
    void redisFastPathDuplicate_returnsEarlyWithoutDbHit() {
        when(webhookDedupService.checkByEventId("gateway", EVENT_ID))
                .thenReturn(DedupResult.DUPLICATE);

        WebhookIngestResult result = service.ingestWebhookEvent(PAYLOAD, "any-sig");

        assertThat(result).isEqualTo(WebhookIngestResult.DUPLICATE);
        // DB should never be touched for a Redis fast-path duplicate
        verifyNoInteractions(webhookEventRepository);
    }

    // ── DB-processed duplicate (Redis cold, DB warm) ──────────────────────

    @Test
    @DisplayName("returns DUPLICATE when Redis is cold but DB has a processed event row")
    void dbProcessedDuplicate_returnsWithoutReprocessing() {
        when(webhookDedupService.checkByEventId("gateway", EVENT_ID))
                .thenReturn(DedupResult.NEW);   // Redis says new

        WebhookEvent processedRow = WebhookEvent.builder()
                .eventId(EVENT_ID).processed(true).build();
        when(webhookEventRepository.findByEventId(EVENT_ID))
                .thenReturn(Optional.of(processedRow));

        WebhookIngestResult result = service.ingestWebhookEvent(PAYLOAD, "any-sig");

        assertThat(result).isEqualTo(WebhookIngestResult.DUPLICATE);
        // No signature verification needed for an already-processed event
        verifyNoInteractions(signatureService);
    }

    // ── New event processed ───────────────────────────────────────────────

    @Test
    @DisplayName("processes a new event end-to-end and seeds the Redis dedup marker after success")
    void newEvent_processedAndRedisSeeded() {
        when(webhookDedupService.checkByEventId("gateway", EVENT_ID))
                .thenReturn(DedupResult.NEW);
        when(webhookEventRepository.findByEventId(EVENT_ID)).thenReturn(Optional.empty());
        when(signatureService.verify(anyString(), anyString())).thenReturn(true);
        when(webhookEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Stub the payment-intent state machine
        com.firstclub.payments.entity.PaymentIntent pi =
                com.firstclub.payments.entity.PaymentIntent.builder()
                        .id(1L).amount(new java.math.BigDecimal("500.00")).currency("INR")
                        .status(PaymentIntentStatus.PROCESSING).build();
        when(paymentIntentService.findEntityById(1L)).thenReturn(pi);
        when(paymentRepository.existsByGatewayTxnId("txn_001")).thenReturn(true);

        WebhookIngestResult result = service.ingestWebhookEvent(PAYLOAD, "valid-sig");

        assertThat(result).isEqualTo(WebhookIngestResult.PROCESSED);
        verify(webhookDedupService).recordWebhookReceived("gateway", EVENT_ID);
    }

    // ── Payload-hash fallback (no event-id) ──────────────────────────────

    @Test
    @DisplayName("uses payload-hash dedup and returns DUPLICATE when hash is already known")
    void payloadHashFallback_returnsEarlyOnRedisHit() throws Exception {
        String blankEventIdPayload = """
                {"eventId":"","eventType":"PAYMENT_INTENT.SUCCEEDED",
                 "paymentIntentId":1,"gatewayTxnId":"txn_002","timestamp":"2024-01-01T00:00:00"}
                """;
        String expectedHash = new WebhookDedupService(null, null, null)
                .computePayloadHash(blankEventIdPayload.trim());

        when(webhookDedupService.computePayloadHash(anyString())).thenReturn(expectedHash);
        when(webhookDedupService.checkByPayloadHash("gateway", expectedHash))
                .thenReturn(DedupResult.DUPLICATE);

        WebhookIngestResult result = service.ingestWebhookEvent(blankEventIdPayload, "any-sig");

        assertThat(result).isEqualTo(WebhookIngestResult.DUPLICATE);
        verifyNoInteractions(webhookEventRepository);
    }

    // ── processStoredEvent — invalid-signature guard ──────────────────────

    @Test
    @DisplayName("processStoredEvent: does nothing when signatureValid=false (defense-in-depth)")
    void processStoredEvent_invalidSignature_skipped() {
        WebhookEvent invalidSigEvent = WebhookEvent.builder()
                .eventId("evt_bad_sig")
                .payload(PAYLOAD)
                .processed(false)
                .signatureValid(false)
                .build();

        service.processStoredEvent(invalidSigEvent);

        // Must not touch the DB, payment service, or any downstream component
        verifyNoInteractions(paymentIntentService);
        verifyNoInteractions(paymentRepository);
        verify(webhookEventRepository, never()).save(any());
        assertThat(invalidSigEvent.isProcessed()).isFalse();
    }

    @Test
    @DisplayName("processStoredEvent: skips already-processed events")
    void processStoredEvent_alreadyProcessed_skipped() {
        WebhookEvent processedEvent = WebhookEvent.builder()
                .eventId("evt_done")
                .payload(PAYLOAD)
                .processed(true)
                .signatureValid(true)
                .build();

        service.processStoredEvent(processedEvent);

        verifyNoInteractions(paymentIntentService);
        verifyNoInteractions(paymentRepository);
    }
}
