package com.firstclub.payments.service.impl;

import com.firstclub.customer.exception.CustomerException;
import com.firstclub.customer.repository.CustomerRepository;
import com.firstclub.merchant.exception.MerchantException;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import com.firstclub.payments.dto.PaymentAttemptResponseDTO;
import com.firstclub.payments.dto.PaymentIntentConfirmRequestDTO;
import com.firstclub.payments.dto.PaymentIntentCreateRequestDTO;
import com.firstclub.payments.dto.PaymentIntentV2ResponseDTO;
import com.firstclub.payments.entity.CaptureMode;
import com.firstclub.payments.entity.FailureCategory;
import com.firstclub.payments.entity.PaymentAttempt;
import com.firstclub.payments.entity.PaymentAttemptStatus;
import com.firstclub.payments.entity.PaymentIntentStatusV2;
import com.firstclub.payments.entity.PaymentIntentV2;
import com.firstclub.payments.entity.PaymentMethod;
import com.firstclub.payments.exception.PaymentIntentException;
import com.firstclub.payments.exception.PaymentMethodException;
import com.firstclub.payments.gateway.GatewayResult;
import com.firstclub.payments.gateway.PaymentGatewayCallService;
import com.firstclub.payments.recovery.PaymentOutcomeReconciler;
import com.firstclub.payments.routing.dto.RoutingDecisionDTO;
import com.firstclub.payments.routing.exception.RoutingException;
import com.firstclub.payments.routing.service.PaymentRoutingService;
import com.firstclub.payments.mapper.PaymentAttemptMapper;
import com.firstclub.payments.mapper.PaymentIntentV2Mapper;
import com.firstclub.payments.repository.PaymentAttemptRepository;
import com.firstclub.payments.repository.PaymentIntentV2Repository;
import com.firstclub.payments.repository.PaymentMethodRepository;
import com.firstclub.payments.service.PaymentAttemptService;
import com.firstclub.payments.service.PaymentIntentV2Service;
import com.firstclub.risk.entity.RiskAction;
import com.firstclub.risk.entity.RiskEvent;
import com.firstclub.risk.service.RiskContext;
import com.firstclub.risk.service.RiskDecisionService;
import com.firstclub.risk.service.RiskViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class PaymentIntentV2ServiceImpl implements PaymentIntentV2Service {

    private final PaymentIntentV2Repository paymentIntentV2Repository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final MerchantAccountRepository merchantAccountRepository;
    private final CustomerRepository customerRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final PaymentIntentV2Mapper paymentIntentV2Mapper;
    private final PaymentAttemptMapper paymentAttemptMapper;
    private final PaymentAttemptService paymentAttemptService;
    private final PaymentRoutingService paymentRoutingService;
    private final RiskDecisionService riskDecisionService;
    private final PaymentGatewayCallService gatewayCallService;
    private final PaymentOutcomeReconciler paymentOutcomeReconciler;

    // ── Create ────────────────────────────────────────────────────────────────

    @Override
    public PaymentIntentV2ResponseDTO createPaymentIntent(Long merchantId,
                                                           String idempotencyKey,
                                                           PaymentIntentCreateRequestDTO request) {
        // Idempotency: return existing if the key was seen before for this merchant
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<PaymentIntentV2> existing =
                    paymentIntentV2Repository.findByIdempotencyKeyAndMerchantId(
                            idempotencyKey, merchantId);
            if (existing.isPresent()) {
                log.info("Idempotent create: returning existing intent {} for key {}",
                        existing.get().getId(), idempotencyKey);
                return paymentIntentV2Mapper.toResponseDTO(existing.get());
            }
        }

        var merchant = merchantAccountRepository.findById(merchantId)
                .orElseThrow(() -> MerchantException.merchantNotFound(merchantId));
        var customer = customerRepository
                .findByMerchantIdAndId(merchantId, request.getCustomerId())
                .orElseThrow(() -> CustomerException.customerNotFound(
                        merchantId, request.getCustomerId()));

        PaymentMethod paymentMethod = null;
        if (request.getPaymentMethodId() != null) {
            paymentMethod = paymentMethodRepository
                    .findByMerchantIdAndCustomerIdAndId(
                            merchantId, request.getCustomerId(), request.getPaymentMethodId())
                    .orElseThrow(() -> PaymentMethodException.notFound(
                            merchantId, request.getCustomerId(), request.getPaymentMethodId()));
        }

        String clientSecret = generateClientSecret();
        PaymentIntentStatusV2 initialStatus = (paymentMethod != null)
                ? PaymentIntentStatusV2.REQUIRES_CONFIRMATION
                : PaymentIntentStatusV2.REQUIRES_PAYMENT_METHOD;

        CaptureMode captureMode = request.getCaptureMode() != null
                ? request.getCaptureMode()
                : CaptureMode.AUTO;

        PaymentIntentV2 intent = PaymentIntentV2.builder()
                .merchant(merchant)
                .customer(customer)
                .invoiceId(request.getInvoiceId())
                .subscriptionId(request.getSubscriptionId())
                .paymentMethod(paymentMethod)
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .status(initialStatus)
                .captureMode(captureMode)
                .clientSecret(clientSecret)
                .idempotencyKey(idempotencyKey)
                .metadataJson(request.getMetadataJson())
                .build();

        PaymentIntentV2 saved = paymentIntentV2Repository.save(intent);
        log.info("Created payment intent {} (status={}) for merchant {} customer {}",
                saved.getId(), saved.getStatus(), merchantId, request.getCustomerId());
        return paymentIntentV2Mapper.toResponseDTO(saved);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PaymentIntentV2ResponseDTO getPaymentIntent(Long merchantId, Long id) {
        return paymentIntentV2Mapper.toResponseDTO(loadIntent(merchantId, id));
    }

    // ── Confirm ───────────────────────────────────────────────────────────────

    @Override
    public PaymentIntentV2ResponseDTO confirmPaymentIntent(Long merchantId, Long id,
                                                            PaymentIntentConfirmRequestDTO request) {
        PaymentIntentV2 intent = loadIntent(merchantId, id);

        // ── Idempotent success: intent already succeeded ──────────────────────
        if (intent.getStatus() == PaymentIntentStatusV2.SUCCEEDED) {
            log.info("Idempotent confirm: intent {} is already SUCCEEDED", id);
            return paymentIntentV2Mapper.toResponseDTO(intent);
        }

        // ── Hard terminal states (cancelled) ─────────────────────────────────
        if (intent.getStatus() == PaymentIntentStatusV2.CANCELLED) {
            throw PaymentIntentException.invalidTransition(intent.getStatus(), "confirm");
        }

        // ── FAILED state: only allow if last attempt was retriable ────────────
        if (intent.getStatus() == PaymentIntentStatusV2.FAILED) {
            Optional<PaymentAttempt> lastAttempt =
                    paymentAttemptRepository.findTopByPaymentIntentIdOrderByAttemptNumberDesc(id);
            boolean retriable = lastAttempt
                    .map(a -> a.getStatus() == PaymentAttemptStatus.FAILED && a.isRetriable())
                    .orElse(false);
            if (!retriable) {
                throw PaymentIntentException.nonRetriableFailure(id);
            }
            // retriable — fall through to confirm flow
        } else if (!intent.getStatus().allowsConfirm()) {
            // PROCESSING, REQUIRES_ACTION, or any unknown state
            throw PaymentIntentException.invalidTransition(intent.getStatus(), "confirm");
        }

        // ── Resolve payment method ─────────────────────────────────────────────
        PaymentMethod pm = resolvePaymentMethod(intent, request, merchantId);

        // Attach PM to intent if not already set or switched
        boolean pmChanged = intent.getPaymentMethod() == null
                || !pm.getId().equals(intent.getPaymentMethod().getId());
        if (pmChanged) {
            intent.setPaymentMethod(pm);
            if (intent.getStatus() == PaymentIntentStatusV2.REQUIRES_PAYMENT_METHOD) {
                intent.setStatus(PaymentIntentStatusV2.REQUIRES_CONFIRMATION);
            }
        }
        // ── Risk evaluation ────────────────────────────────────────────────────────
        RiskContext riskContext = new RiskContext(
                intent.getMerchant().getId(), intent.getId(),
                intent.getCustomer().getId(), null, null);
        var riskDecision = riskDecisionService.evaluateForPaymentIntent(riskContext);
        log.info("Risk decision for intent {}: {} (score={})",
                id, riskDecision.decision(), riskDecision.score());

        if (riskDecision.decision() == RiskAction.BLOCK) {
            intent.setStatus(PaymentIntentStatusV2.FAILED);
            paymentIntentV2Repository.save(intent);
            throw new RiskViolationException(RiskEvent.RiskEventType.IP_BLOCKED,
                    "Payment blocked by risk engine");
        }
        if (riskDecision.decision() == RiskAction.REVIEW
                || riskDecision.decision() == RiskAction.CHALLENGE) {
            intent.setStatus(PaymentIntentStatusV2.REQUIRES_ACTION);
            paymentIntentV2Repository.save(intent);
            return paymentIntentV2Mapper.toResponseDTO(intent);
        }
        // ── Create attempt ─────────────────────────────────────────────────────
        int nextAttemptNumber = paymentAttemptService.computeNextAttemptNumber(id);

        // Select gateway via routing service; fall back to request hint if no rules are configured
        RoutingDecisionDTO routingDecision = null;
        String selectedGateway;
        try {
            routingDecision = paymentRoutingService.selectGatewayForAttempt(
                    intent, pm, nextAttemptNumber);
            selectedGateway = routingDecision.getSelectedGateway();
            log.debug("Routing selected gateway '{}' (ruleId={}, isFallback={}) for intent {}",
                    selectedGateway, routingDecision.getRuleId(), routingDecision.isFallback(), id);
        } catch (RoutingException re) {
            if (request.getGatewayName() != null && !request.getGatewayName().isBlank()) {
                selectedGateway = request.getGatewayName();
                log.warn("No routing rules matched for intent {}; using request hint '{}'",
                        id, selectedGateway);
            } else {
                throw re;
            }
        }

        PaymentAttempt attempt = paymentAttemptService.createAttempt(
                intent, nextAttemptNumber, selectedGateway);

        // Persist the routing decision snapshot on the attempt for audit
        if (routingDecision != null && routingDecision.getSnapshotJson() != null) {
            attempt.setRoutingSnapshotJson(routingDecision.getSnapshotJson());
            paymentAttemptRepository.save(attempt);
        }

        // ── Advance intent to PROCESSING ───────────────────────────────────────
        intent.setStatus(PaymentIntentStatusV2.PROCESSING);
        paymentIntentV2Repository.save(intent);

        // ── Call gateway ──────────────────────────────────────────────────────
        // PaymentGatewayCallService stamps attempt with idempotencyKey / startedAt /
        // processorNodeId and returns a GatewayResult with the outcome.
        GatewayResult gatewayResult = gatewayCallService.submitPayment(attempt, intent);
        long latencyMs = gatewayResult.latencyMs() != null ? gatewayResult.latencyMs() : 0L;

        // Persist gateway-level fields added by the call service before status update
        if (gatewayResult.gatewayTransactionId() != null) {
            attempt.setGatewayTransactionId(gatewayResult.gatewayTransactionId());
        }
        if (gatewayResult.rawResponseJson() != null) {
            attempt.setResponsePayloadJson(gatewayResult.rawResponseJson());
        }
        if (gatewayResult.gatewayReference() != null) {
            attempt.setGatewayReference(gatewayResult.gatewayReference());
        }
        paymentAttemptRepository.save(attempt);

        // ── Update attempt and intent based on gateway result ─────────────────
        if (gatewayResult.isSucceeded()) {
            paymentAttemptService.markCaptured(
                    attempt.getId(), id, "SUCCESS", latencyMs);

            intent.setLastSuccessfulAttemptId(attempt.getId());
            intent.setStatus(PaymentIntentStatusV2.SUCCEEDED);
            log.info("Payment intent {} SUCCEEDED on attempt #{}", id, nextAttemptNumber);

        } else if (gatewayResult.needsReconciliation()) {
            paymentAttemptService.markUnknown(attempt.getId(), id, latencyMs);

            intent.setReconciliationState("PENDING");
            intent.setStatus(PaymentIntentStatusV2.PROCESSING);  // still processing — recovery will resolve
            log.warn("Payment intent {} attempt #{} outcome UNKNOWN — queued for reconciliation",
                    id, nextAttemptNumber);

        } else {
            boolean retriable = gatewayResult.failureCategory() != null
                    && gatewayResult.failureCategory().isTypicallyRetriable();
            paymentAttemptService.markFailed(
                    attempt.getId(), id,
                    gatewayResult.responseCode(), gatewayResult.responseMessage(),
                    gatewayResult.failureCategory(), retriable, latencyMs);

            intent.setStatus(PaymentIntentStatusV2.FAILED);
            log.info("Payment intent {} FAILED on attempt #{} (retriable={})",
                    id, nextAttemptNumber, retriable);
        }

        PaymentIntentV2 updated = paymentIntentV2Repository.save(intent);
        return paymentIntentV2Mapper.toResponseDTO(updated);
    }

    // ── Reconcile gateway status ───────────────────────────────────────────────

    @Override
    public PaymentIntentV2ResponseDTO reconcileGatewayStatus(Long merchantId, Long id) {
        loadIntent(merchantId, id); // validates merchant-scoped access
        int processed = paymentOutcomeReconciler.reconcileIntent(id);
        log.info("reconcileGatewayStatus: processed {} UNKNOWN attempt(s) for intent {}", processed, id);
        return paymentIntentV2Mapper.toResponseDTO(loadIntent(merchantId, id));
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    @Override
    public PaymentIntentV2ResponseDTO cancelPaymentIntent(Long merchantId, Long id) {
        PaymentIntentV2 intent = loadIntent(merchantId, id);

        if (!intent.getStatus().allowsCancel()) {
            throw PaymentIntentException.invalidTransition(intent.getStatus(), "cancel");
        }

        intent.setStatus(PaymentIntentStatusV2.CANCELLED);
        PaymentIntentV2 updated = paymentIntentV2Repository.save(intent);
        log.info("Cancelled payment intent {} for merchant {}", id, merchantId);
        return paymentIntentV2Mapper.toResponseDTO(updated);
    }

    // ── Attempts ──────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<PaymentAttemptResponseDTO> listAttempts(Long merchantId, Long id) {
        loadIntent(merchantId, id); // validates merchant-scoped access
        return paymentAttemptRepository
                .findByPaymentIntentIdOrderByAttemptNumberAsc(id)
                .stream()
                .map(paymentAttemptMapper::toResponseDTO)
                .toList();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private PaymentIntentV2 loadIntent(Long merchantId, Long id) {
        return paymentIntentV2Repository.findByMerchantIdAndId(merchantId, id)
                .orElseThrow(() -> PaymentIntentException.notFound(merchantId, id));
    }

    private PaymentMethod resolvePaymentMethod(PaymentIntentV2 intent,
                                                PaymentIntentConfirmRequestDTO request,
                                                Long merchantId) {
        Long pmId = request.getPaymentMethodId() != null
                ? request.getPaymentMethodId()
                : (intent.getPaymentMethod() != null ? intent.getPaymentMethod().getId() : null);

        if (pmId == null) {
            throw PaymentIntentException.missingPaymentMethod(intent.getId());
        }

        return paymentMethodRepository
                .findByMerchantIdAndCustomerIdAndId(
                        merchantId, intent.getCustomer().getId(), pmId)
                .orElseThrow(() -> PaymentMethodException.notFound(
                        merchantId, intent.getCustomer().getId(), pmId));
    }

    /** @return a 128-character URL-safe client secret (two UUIDs concatenated). */
    private static String generateClientSecret() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }
}
