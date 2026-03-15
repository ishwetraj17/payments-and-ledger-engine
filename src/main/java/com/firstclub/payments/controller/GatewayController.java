package com.firstclub.payments.controller;

import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.payments.dto.GatewayOtpConfirmRequest;
import com.firstclub.payments.dto.GatewayPayRequest;
import com.firstclub.payments.dto.PaymentIntentDTO;
import com.firstclub.payments.entity.PaymentIntent;
import com.firstclub.payments.service.GatewaySimulatorService;
import com.firstclub.payments.service.PaymentIntentService;
import com.firstclub.risk.entity.RiskEvent.RiskEventType;
import com.firstclub.risk.service.RiskService;
import com.firstclub.risk.service.RiskViolationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Fake payment-gateway endpoints used for local development and integration
 * testing.  Disabled by default; enable via {@code app.gateway-emulator.enabled=true}
 * (set in application-dev.properties — never in production).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /gateway/pay}         — simulate an initial charge attempt
 *   <li>{@code POST /gateway/otp/confirm} — simulate completing an OTP / 3-DS challenge
 * </ul>
 */
@ConditionalOnProperty(name = "app.gateway-emulator.enabled", havingValue = "true", matchIfMissing = false)
@RestController
@RequestMapping("/gateway")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Gateway Emulator", description = "Fake payment-gateway for dev/testing only")
public class GatewayController {

    private final PaymentIntentService paymentIntentService;
    private final GatewaySimulatorService gatewaySimulatorService;
    private final RiskService riskService;
    private final InvoiceRepository invoiceRepository;

    // -------------------------------------------------------------------------
    // POST /gateway/pay
    // -------------------------------------------------------------------------

    @Operation(summary = "Simulate a gateway charge",
               description = "Advances the PaymentIntent state and schedules a signed webhook callback in 2-5 seconds.")
    @PostMapping("/pay")
    public ResponseEntity<Map<String, Object>> pay(
            @Valid @RequestBody GatewayPayRequest request,
            HttpServletRequest httpRequest) {

        // Risk checks: velocity + IP block before processing the payment
        String ip       = resolveClientIp(httpRequest);
        String deviceId = httpRequest.getHeader("X-Device-Id");

        PaymentIntentDTO dto = paymentIntentService.findById(request.paymentIntentId());

        // Resolve user ID from invoice when available (enables velocity tracking)
        Long userId = null;
        if (dto.getInvoiceId() != null) {
            userId = invoiceRepository.findById(dto.getInvoiceId())
                    .map(inv -> inv.getUserId())
                    .orElse(null);
        }

        try {
            riskService.checkAndRecord(userId, ip, deviceId);
        } catch (RiskViolationException rve) {
            HttpStatus status = rve.getType() == RiskEventType.IP_BLOCKED
                    ? HttpStatus.FORBIDDEN
                    : HttpStatus.TOO_MANY_REQUESTS;
            return ResponseEntity.status(status).body(Map.of(
                    "errorCode", rve.getType().name(),
                    "message",   rve.getMessage()
            ));
        }

        String webhookEventType;
        switch (request.outcome()) {
            case "SUCCEEDED" -> {
                paymentIntentService.markProcessing(request.paymentIntentId());
                webhookEventType = "PAYMENT_INTENT.SUCCEEDED";
            }
            case "FAILED" -> {
                paymentIntentService.markProcessing(request.paymentIntentId());
                webhookEventType = "PAYMENT_INTENT.FAILED";
            }
            case "REQUIRES_ACTION" -> {
                paymentIntentService.markRequiresAction(request.paymentIntentId());
                webhookEventType = null;   // webhook fires only after OTP confirm
            }
            default -> throw new IllegalStateException("Unexpected outcome: " + request.outcome());
        }

        // Re-read to get the updated status for the response
        PaymentIntentDTO updated = paymentIntentService.findById(request.paymentIntentId());

        if (webhookEventType != null) {
            // Fetch entity for the simulator (needs amount/currency)
            PaymentIntent piEntity = paymentIntentService.findEntityById(request.paymentIntentId());
            gatewaySimulatorService.scheduleWebhookDispatch(piEntity, webhookEventType);
            log.info("Gateway: PI {} outcome={}, webhook {} scheduled", request.paymentIntentId(),
                    request.outcome(), webhookEventType);
        }

        return ResponseEntity.accepted().body(Map.of(
                "paymentIntentId", updated.getId(),
                "status",         updated.getStatus().name(),
                "message",        outcomeMessage(request.outcome())
        ));
    }

    // -------------------------------------------------------------------------
    // POST /gateway/otp/confirm
    // -------------------------------------------------------------------------

    @Operation(summary = "Confirm OTP / 3-DS challenge",
               description = "Moves a REQUIRES_ACTION intent to PROCESSING and schedules a SUCCEEDED webhook.")
    @PostMapping("/otp/confirm")
    public ResponseEntity<Map<String, Object>> confirmOtp(
            @Valid @RequestBody GatewayOtpConfirmRequest request) {

        paymentIntentService.markProcessing(request.paymentIntentId());
        PaymentIntent piEntity = paymentIntentService.findEntityById(request.paymentIntentId());
        gatewaySimulatorService.scheduleWebhookDispatch(piEntity, "PAYMENT_INTENT.SUCCEEDED");

        log.info("Gateway: PI {} OTP confirmed, SUCCEEDED webhook scheduled", request.paymentIntentId());

        return ResponseEntity.accepted().body(Map.of(
                "paymentIntentId", request.paymentIntentId(),
                "status",         "PROCESSING",
                "message",        "OTP confirmed — payment is being processed"
        ));
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static String outcomeMessage(String outcome) {
        return switch (outcome) {
            case "SUCCEEDED"       -> "Payment is being processed — await SUCCEEDED webhook";
            case "FAILED"          -> "Payment failed — await FAILED webhook";
            case "REQUIRES_ACTION" -> "3-DS / OTP challenge required";
            default                -> outcome;
        };
    }

    /**
     * Resolves the real client IP, honouring {@code X-Forwarded-For} when set
     * by a trusted proxy.  Falls back to {@code getRemoteAddr()}.
     * SSRF note: only the <em>first</em> value of X-Forwarded-For is used to
     * avoid a spoofed override via chained proxies.
     */
    private static String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String candidate = xff.split(",")[0].strip();
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }
        return request.getRemoteAddr();
    }
}
