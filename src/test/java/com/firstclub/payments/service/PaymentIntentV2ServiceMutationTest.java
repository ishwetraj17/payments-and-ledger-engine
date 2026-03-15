package com.firstclub.payments.service;

import com.firstclub.customer.entity.Customer;
import com.firstclub.customer.entity.CustomerStatus;
import com.firstclub.customer.repository.CustomerRepository;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantStatus;
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
import com.firstclub.payments.entity.PaymentMethodStatus;
import com.firstclub.payments.entity.PaymentMethodType;
import com.firstclub.payments.exception.PaymentIntentException;
import com.firstclub.payments.gateway.GatewayResult;
import com.firstclub.payments.gateway.PaymentGatewayCallService;
import com.firstclub.payments.mapper.PaymentAttemptMapper;
import com.firstclub.payments.mapper.PaymentIntentV2Mapper;
import com.firstclub.payments.recovery.PaymentOutcomeReconciler;
import com.firstclub.payments.repository.PaymentAttemptRepository;
import com.firstclub.payments.repository.PaymentIntentV2Repository;
import com.firstclub.payments.repository.PaymentMethodRepository;
import com.firstclub.payments.routing.dto.RoutingDecisionDTO;
import com.firstclub.payments.routing.exception.RoutingException;
import com.firstclub.payments.routing.service.PaymentRoutingService;
import com.firstclub.payments.service.impl.PaymentIntentV2ServiceImpl;
import com.firstclub.risk.dto.RiskDecisionResponseDTO;
import com.firstclub.risk.entity.RiskAction;
import com.firstclub.risk.service.RiskDecisionService;
import com.firstclub.risk.service.RiskViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Mutation-killing tests for {@link PaymentIntentV2ServiceImpl}.
 *
 * <p>Targets surviving and no-coverage mutants identified by PIT baseline:
 * <ul>
 *   <li>createPaymentIntent — idempotency key conditions, captureMode default, clientSecret, return value</li>
 *   <li>confirmPaymentIntent — risk BLOCK/REVIEW/CHALLENGE, gateway result branches, PM resolution,
 *       routing fallback, gateway field propagation, lastSuccessfulAttemptId, reconciliationState</li>
 *   <li>cancelPaymentIntent — return value assertion</li>
 *   <li>getPaymentIntent — no-coverage path</li>
 *   <li>listAttempts — no-coverage path</li>
 *   <li>reconcileGatewayStatus — no-coverage path</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentIntentV2ServiceImpl — Mutation-killing tests")
class PaymentIntentV2ServiceMutationTest {

    @Mock private PaymentIntentV2Repository paymentIntentV2Repository;
    @Mock private PaymentAttemptRepository paymentAttemptRepository;
    @Mock private MerchantAccountRepository merchantAccountRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private PaymentMethodRepository paymentMethodRepository;
    @Mock private PaymentIntentV2Mapper paymentIntentV2Mapper;
    @Mock private PaymentAttemptMapper paymentAttemptMapper;
    @Mock private PaymentAttemptService paymentAttemptService;
    @Mock private PaymentRoutingService paymentRoutingService;
    @Mock private RiskDecisionService riskDecisionService;
    @Mock private PaymentGatewayCallService gatewayCallService;
    @Mock private PaymentOutcomeReconciler paymentOutcomeReconciler;

    @InjectMocks
    private PaymentIntentV2ServiceImpl service;

    private static final Long MERCHANT_ID = 1L;
    private static final Long CUSTOMER_ID = 5L;
    private static final Long PM_ID       = 10L;
    private static final Long PM_ID_2     = 20L;
    private static final Long INTENT_ID   = 100L;

    private MerchantAccount merchant;
    private Customer customer;
    private PaymentMethod paymentMethod;
    private PaymentMethod paymentMethod2;
    private PaymentIntentV2ResponseDTO dummyResponse;

    @BeforeEach
    void setUp() {
        merchant = MerchantAccount.builder()
                .id(MERCHANT_ID).merchantCode("M1").legalName("Test Merchant")
                .status(MerchantStatus.ACTIVE).build();

        customer = Customer.builder()
                .id(CUSTOMER_ID).merchant(merchant).email("c@test.com")
                .status(CustomerStatus.ACTIVE).build();

        paymentMethod = PaymentMethod.builder()
                .id(PM_ID).merchant(merchant).customer(customer)
                .methodType(PaymentMethodType.CARD).providerToken("tok_test")
                .provider("razorpay").status(PaymentMethodStatus.ACTIVE).build();

        paymentMethod2 = PaymentMethod.builder()
                .id(PM_ID_2).merchant(merchant).customer(customer)
                .methodType(PaymentMethodType.CARD).providerToken("tok_test_2")
                .provider("razorpay").status(PaymentMethodStatus.ACTIVE).build();

        dummyResponse = PaymentIntentV2ResponseDTO.builder()
                .id(INTENT_ID).merchantId(MERCHANT_ID).customerId(CUSTOMER_ID).build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private PaymentIntentV2 buildIntent(PaymentIntentStatusV2 status, PaymentMethod pm) {
        return PaymentIntentV2.builder()
                .id(INTENT_ID)
                .merchant(merchant)
                .customer(customer)
                .paymentMethod(pm)
                .amount(new BigDecimal("999.00"))
                .currency("INR")
                .status(status)
                .captureMode(CaptureMode.AUTO)
                .clientSecret("secret123")
                .build();
    }

    private PaymentIntentCreateRequestDTO buildCreateRequest(Long pmId) {
        return PaymentIntentCreateRequestDTO.builder()
                .customerId(CUSTOMER_ID)
                .amount(new BigDecimal("999.00"))
                .currency("INR")
                .captureMode(CaptureMode.AUTO)
                .paymentMethodId(pmId)
                .build();
    }

    private PaymentIntentConfirmRequestDTO confirmRequest() {
        return PaymentIntentConfirmRequestDTO.builder()
                .gatewayName("razorpay")
                .build();
    }

    private PaymentIntentConfirmRequestDTO confirmRequestWithPm(Long pmId) {
        return PaymentIntentConfirmRequestDTO.builder()
                .paymentMethodId(pmId)
                .gatewayName("razorpay")
                .build();
    }

    private void stubMerchantAndCustomer() {
        when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
        when(customerRepository.findByMerchantIdAndId(MERCHANT_ID, CUSTOMER_ID))
                .thenReturn(Optional.of(customer));
    }

    private void stubIntentLoad(PaymentIntentV2 intent) {
        when(paymentIntentV2Repository.findByMerchantIdAndId(MERCHANT_ID, INTENT_ID))
                .thenReturn(Optional.of(intent));
    }

    private void stubSavePassThrough() {
        when(paymentIntentV2Repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private RiskDecisionResponseDTO allowDecision() {
        return new RiskDecisionResponseDTO(1L, MERCHANT_ID, INTENT_ID, CUSTOMER_ID,
                0, RiskAction.ALLOW, "[]", null);
    }

    private RiskDecisionResponseDTO blockDecision() {
        return new RiskDecisionResponseDTO(2L, MERCHANT_ID, INTENT_ID, CUSTOMER_ID,
                90, RiskAction.BLOCK, "[]", null);
    }

    private RiskDecisionResponseDTO reviewDecision() {
        return new RiskDecisionResponseDTO(3L, MERCHANT_ID, INTENT_ID, CUSTOMER_ID,
                50, RiskAction.REVIEW, "[]", null);
    }

    private RiskDecisionResponseDTO challengeDecision() {
        return new RiskDecisionResponseDTO(4L, MERCHANT_ID, INTENT_ID, CUSTOMER_ID,
                40, RiskAction.CHALLENGE, "[]", null);
    }

    private PaymentAttempt buildAttempt(PaymentIntentV2 intent) {
        return PaymentAttempt.builder()
                .id(1L).paymentIntent(intent).attemptNumber(1)
                .gatewayName("razorpay").status(PaymentAttemptStatus.STARTED).build();
    }

    /** Stubs the confirm path through risk → routing fallback → gateway succeeded. */
    private PaymentAttempt stubConfirmSuccessPath(PaymentIntentV2 intent) {
        when(riskDecisionService.evaluateForPaymentIntent(any())).thenReturn(allowDecision());
        when(paymentRoutingService.selectGatewayForAttempt(any(), any(), anyInt()))
                .thenThrow(RoutingException.noEligibleGateway("CARD", "INR"));
        PaymentAttempt attempt = buildAttempt(intent);
        when(paymentAttemptService.computeNextAttemptNumber(INTENT_ID)).thenReturn(1);
        when(paymentAttemptService.createAttempt(eq(intent), eq(1), anyString()))
                .thenReturn(attempt);
        when(gatewayCallService.submitPayment(any(), any()))
                .thenReturn(GatewayResult.succeeded("TXN-001", "SUCCESS", 100L));
        when(paymentAttemptService.markCaptured(eq(1L), eq(INTENT_ID), anyString(), anyLong()))
                .thenReturn(attempt);
        when(paymentAttemptRepository.save(any())).thenReturn(attempt);
        return attempt;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // createPaymentIntent
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("createPaymentIntent — mutation killers")
    class CreateMutantKillers {

        @Test
        @DisplayName("null idempotencyKey bypasses idempotency check and creates new intent")
        void nullIdempotencyKey_createsNewIntent() {
            // Kills: line 73 RemoveConditional_IF→true (idempotencyKey != null)
            stubMerchantAndCustomer();
            when(paymentIntentV2Repository.save(any())).thenAnswer(inv -> {
                PaymentIntentV2 pi = inv.getArgument(0);
                pi.setId(INTENT_ID);
                return pi;
            });
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            PaymentIntentV2ResponseDTO result =
                    service.createPaymentIntent(MERCHANT_ID, null, buildCreateRequest(null));

            // Kills: line 127 NullReturn
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(INTENT_ID);
            verify(paymentIntentV2Repository).save(any());
            // Must never call findByIdempotencyKeyAndMerchantId when key is null
            verify(paymentIntentV2Repository, never())
                    .findByIdempotencyKeyAndMerchantId(any(), any());
        }

        @Test
        @DisplayName("blank idempotencyKey bypasses idempotency check and creates new intent")
        void blankIdempotencyKey_createsNewIntent() {
            // Kills: line 73 RemoveConditional_IF→true (!idempotencyKey.isBlank())
            stubMerchantAndCustomer();
            when(paymentIntentV2Repository.save(any())).thenAnswer(inv -> {
                PaymentIntentV2 pi = inv.getArgument(0);
                pi.setId(INTENT_ID);
                return pi;
            });
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            PaymentIntentV2ResponseDTO result =
                    service.createPaymentIntent(MERCHANT_ID, "   ", buildCreateRequest(null));

            assertThat(result).isNotNull();
            verify(paymentIntentV2Repository).save(any());
            verify(paymentIntentV2Repository, never())
                    .findByIdempotencyKeyAndMerchantId(any(), any());
        }

        @Test
        @DisplayName("null captureMode on request defaults to AUTO")
        void nullCaptureMode_defaultsToAuto() {
            // Kills: line 105 RemoveConditional_ELSE→false and _IF→true
            stubMerchantAndCustomer();
            when(paymentIntentV2Repository.findByIdempotencyKeyAndMerchantId(any(), any()))
                    .thenReturn(Optional.empty());
            when(paymentIntentV2Repository.save(any())).thenAnswer(inv -> {
                PaymentIntentV2 pi = inv.getArgument(0);
                pi.setId(INTENT_ID);
                return pi;
            });
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            PaymentIntentCreateRequestDTO request = PaymentIntentCreateRequestDTO.builder()
                    .customerId(CUSTOMER_ID)
                    .amount(new BigDecimal("999.00"))
                    .currency("INR")
                    .captureMode(null) // explicitly null
                    .build();

            service.createPaymentIntent(MERCHANT_ID, "key-cm", request);

            verify(paymentIntentV2Repository).save(argThat(pi ->
                    pi.getCaptureMode() == CaptureMode.AUTO));
        }

        @Test
        @DisplayName("non-null captureMode on request propagates to intent")
        void nonNullCaptureMode_propagates() {
            // Kills: line 105 RemoveConditional_IF→true (swaps to always-null-default)
            stubMerchantAndCustomer();
            when(paymentIntentV2Repository.findByIdempotencyKeyAndMerchantId(any(), any()))
                    .thenReturn(Optional.empty());
            when(paymentIntentV2Repository.save(any())).thenAnswer(inv -> {
                PaymentIntentV2 pi = inv.getArgument(0);
                pi.setId(INTENT_ID);
                return pi;
            });
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            PaymentIntentCreateRequestDTO request = PaymentIntentCreateRequestDTO.builder()
                    .customerId(CUSTOMER_ID)
                    .amount(new BigDecimal("999.00"))
                    .currency("INR")
                    .captureMode(CaptureMode.MANUAL)
                    .build();

            service.createPaymentIntent(MERCHANT_ID, "key-cm2", request);

            verify(paymentIntentV2Repository).save(argThat(pi ->
                    pi.getCaptureMode() == CaptureMode.MANUAL));
        }

        @Test
        @DisplayName("client secret is non-empty on created intent")
        void clientSecret_isNonEmpty() {
            // Kills: line 357 EmptyObjectReturnValsMutator on generateClientSecret
            stubMerchantAndCustomer();
            when(paymentIntentV2Repository.findByIdempotencyKeyAndMerchantId(any(), any()))
                    .thenReturn(Optional.empty());
            when(paymentIntentV2Repository.save(any())).thenAnswer(inv -> {
                PaymentIntentV2 pi = inv.getArgument(0);
                pi.setId(INTENT_ID);
                return pi;
            });
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            service.createPaymentIntent(MERCHANT_ID, "key-cs", buildCreateRequest(null));

            verify(paymentIntentV2Repository).save(argThat(pi ->
                    pi.getClientSecret() != null && !pi.getClientSecret().isEmpty()));
        }

        @Test
        @DisplayName("invoiceId and subscriptionId propagate to saved intent")
        void invoiceIdAndSubscriptionId_propagate() {
            stubMerchantAndCustomer();
            when(paymentIntentV2Repository.findByIdempotencyKeyAndMerchantId(any(), any()))
                    .thenReturn(Optional.empty());
            when(paymentIntentV2Repository.save(any())).thenAnswer(inv -> {
                PaymentIntentV2 pi = inv.getArgument(0);
                pi.setId(INTENT_ID);
                return pi;
            });
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            PaymentIntentCreateRequestDTO request = PaymentIntentCreateRequestDTO.builder()
                    .customerId(CUSTOMER_ID)
                    .amount(new BigDecimal("100.00"))
                    .currency("INR")
                    .captureMode(CaptureMode.AUTO)
                    .invoiceId(42L)
                    .subscriptionId(77L)
                    .metadataJson("{\"ref\":\"abc\"}")
                    .build();

            service.createPaymentIntent(MERCHANT_ID, "key-meta", request);

            verify(paymentIntentV2Repository).save(argThat(pi ->
                    pi.getInvoiceId() != null && pi.getInvoiceId().equals(42L)
                    && pi.getSubscriptionId() != null && pi.getSubscriptionId().equals(77L)
                    && "{\"ref\":\"abc\"}".equals(pi.getMetadataJson())));
        }

        @Test
        @DisplayName("merchant not found → throws MerchantException")
        void merchantNotFound_throws() {
            // Kills: line 85 NullReturnValsMutator on lambda$createPaymentIntent$0
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.createPaymentIntent(MERCHANT_ID, "key-m", buildCreateRequest(null)))
                    .isInstanceOf(com.firstclub.merchant.exception.MerchantException.class);
        }

        @Test
        @DisplayName("customer not found → throws CustomerException")
        void customerNotFound_throws() {
            // Kills: line 88 NullReturnValsMutator on lambda$createPaymentIntent$1
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
            when(customerRepository.findByMerchantIdAndId(MERCHANT_ID, CUSTOMER_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.createPaymentIntent(MERCHANT_ID, "key-c", buildCreateRequest(null)))
                    .isInstanceOf(com.firstclub.customer.exception.CustomerException.class);
        }

        @Test
        @DisplayName("payment method not found on create → throws PaymentMethodException")
        void pmNotFoundOnCreate_throws() {
            // Kills: line 96 NullReturnValsMutator on lambda$createPaymentIntent$2
            stubMerchantAndCustomer();
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.createPaymentIntent(MERCHANT_ID, "key-pm", buildCreateRequest(PM_ID)))
                    .isInstanceOf(com.firstclub.payments.exception.PaymentMethodException.class);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getPaymentIntent
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getPaymentIntent — no-coverage killers")
    class GetPaymentIntentTests {

        @Test
        @DisplayName("returns non-null mapped DTO for valid merchant+intent")
        void returnsNonNull() {
            // Kills: line 135 NullReturnValsMutator
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            stubIntentLoad(intent);
            when(paymentIntentV2Mapper.toResponseDTO(intent)).thenReturn(dummyResponse);

            PaymentIntentV2ResponseDTO result = service.getPaymentIntent(MERCHANT_ID, INTENT_ID);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(INTENT_ID);
        }

        @Test
        @DisplayName("throws when intent not found for merchant")
        void notFound_throws() {
            when(paymentIntentV2Repository.findByMerchantIdAndId(MERCHANT_ID, INTENT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPaymentIntent(MERCHANT_ID, INTENT_ID))
                    .isInstanceOf(PaymentIntentException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "PAYMENT_INTENT_NOT_FOUND");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // confirmPaymentIntent — Risk paths
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("confirmPaymentIntent — risk decision branches")
    class ConfirmRiskTests {

        @Test
        @DisplayName("BLOCK → sets FAILED, saves, and throws RiskViolationException")
        void riskBlock_setsFailed_throws() {
            // Kills: line 192 RemoveConditional, line 193 VoidMethodCall (setStatus FAILED)
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            stubIntentLoad(intent);
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(paymentMethod));
            when(riskDecisionService.evaluateForPaymentIntent(any())).thenReturn(blockDecision());
            stubSavePassThrough();

            assertThatThrownBy(() ->
                    service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest()))
                    .isInstanceOf(RiskViolationException.class);

            assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatusV2.FAILED);
            verify(paymentIntentV2Repository).save(intent);
        }

        @Test
        @DisplayName("REVIEW → sets REQUIRES_ACTION, saves, returns non-null DTO")
        void riskReview_setsRequiresAction() {
            // Kills: line 198-202 RemoveConditional, VoidMethodCall, NullReturn
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            stubIntentLoad(intent);
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(paymentMethod));
            when(riskDecisionService.evaluateForPaymentIntent(any())).thenReturn(reviewDecision());
            stubSavePassThrough();
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            PaymentIntentV2ResponseDTO result =
                    service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest());

            assertThat(result).isNotNull();
            assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatusV2.REQUIRES_ACTION);
            verify(paymentIntentV2Repository).save(intent);
            // Must NOT create attempt
            verify(paymentAttemptService, never()).createAttempt(any(), anyInt(), anyString());
        }

        @Test
        @DisplayName("CHALLENGE → sets REQUIRES_ACTION, saves, returns non-null DTO")
        void riskChallenge_setsRequiresAction() {
            // Kills: line 199 RemoveConditional_ELSE→false (CHALLENGE check)
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            stubIntentLoad(intent);
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(paymentMethod));
            when(riskDecisionService.evaluateForPaymentIntent(any())).thenReturn(challengeDecision());
            stubSavePassThrough();
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            PaymentIntentV2ResponseDTO result =
                    service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest());

            assertThat(result).isNotNull();
            assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatusV2.REQUIRES_ACTION);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // confirmPaymentIntent — State transition guards
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("confirmPaymentIntent — state transition guards")
    class ConfirmStateGuardTests {

        @Test
        @DisplayName("PROCESSING → throws INVALID_PAYMENT_INTENT_TRANSITION")
        void processing_throws() {
            // Kills: line 167 RemoveConditional_ELSE→false (allowsConfirm branch)
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.PROCESSING, paymentMethod);
            stubIntentLoad(intent);

            assertThatThrownBy(() ->
                    service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest()))
                    .isInstanceOf(PaymentIntentException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_PAYMENT_INTENT_TRANSITION");
        }

        @Test
        @DisplayName("REQUIRES_ACTION → throws INVALID_PAYMENT_INTENT_TRANSITION")
        void requiresAction_throws() {
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_ACTION, paymentMethod);
            stubIntentLoad(intent);

            assertThatThrownBy(() ->
                    service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest()))
                    .isInstanceOf(PaymentIntentException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_PAYMENT_INTENT_TRANSITION");
        }

        @Test
        @DisplayName("FAILED with retriable last attempt → proceeds to confirm flow")
        void failed_retriable_proceeds() {
            // Kills: line 161 RemoveConditional_ELSE→false, IF→true
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.FAILED, paymentMethod);
            stubIntentLoad(intent);

            PaymentAttempt lastAttempt = PaymentAttempt.builder()
                    .id(1L).paymentIntent(intent).attemptNumber(1)
                    .status(PaymentAttemptStatus.FAILED).retriable(true)
                    .failureCategory(FailureCategory.NETWORK).build();
            when(paymentAttemptRepository.findTopByPaymentIntentIdOrderByAttemptNumberDesc(INTENT_ID))
                    .thenReturn(Optional.of(lastAttempt));
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(paymentMethod));

            PaymentAttempt newAttempt = stubConfirmSuccessPath(intent);
            stubSavePassThrough();
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            PaymentIntentV2ResponseDTO result =
                    service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest());

            assertThat(result).isNotNull();
            assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatusV2.SUCCEEDED);
        }

        @Test
        @DisplayName("FAILED with no last attempt → throws NON_RETRIABLE_FAILURE")
        void failed_noLastAttempt_throws() {
            // Kills: line 161-163 conditions — orElse(false) path
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.FAILED, paymentMethod);
            stubIntentLoad(intent);
            when(paymentAttemptRepository.findTopByPaymentIntentIdOrderByAttemptNumberDesc(INTENT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest()))
                    .isInstanceOf(PaymentIntentException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "NON_RETRIABLE_FAILURE");
        }

        @Test
        @DisplayName("FAILED with last attempt status not FAILED → throws NON_RETRIABLE_FAILURE")
        void failed_lastAttemptNotFailed_throws() {
            // Kills: line 161 RemoveConditional on a.getStatus() == FAILED
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.FAILED, paymentMethod);
            stubIntentLoad(intent);

            PaymentAttempt lastAttempt = PaymentAttempt.builder()
                    .id(1L).paymentIntent(intent).attemptNumber(1)
                    .status(PaymentAttemptStatus.UNKNOWN).retriable(true).build();
            when(paymentAttemptRepository.findTopByPaymentIntentIdOrderByAttemptNumberDesc(INTENT_ID))
                    .thenReturn(Optional.of(lastAttempt));

            assertThatThrownBy(() ->
                    service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest()))
                    .isInstanceOf(PaymentIntentException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "NON_RETRIABLE_FAILURE");
        }

        @Test
        @DisplayName("REQUIRES_PAYMENT_METHOD with PM in request → confirms successfully")
        void requiresPaymentMethod_withPm_confirms() {
            // Kills: line 180 RemoveConditional (pmChanged + REQUIRES_PAYMENT_METHOD status transition)
            // Kills: line 179 VoidMethodCall (setPaymentMethod)
            // Kills: line 181 VoidMethodCall (setStatus REQUIRES_CONFIRMATION → no longer needed since it goes to PROCESSING)
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_PAYMENT_METHOD, null);
            stubIntentLoad(intent);
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(paymentMethod));

            stubConfirmSuccessPath(intent);
            stubSavePassThrough();
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            PaymentIntentV2ResponseDTO result = service.confirmPaymentIntent(
                    MERCHANT_ID, INTENT_ID, confirmRequestWithPm(PM_ID));

            assertThat(result).isNotNull();
            // PM should be attached
            assertThat(intent.getPaymentMethod()).isEqualTo(paymentMethod);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // confirmPaymentIntent — Payment method resolution
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("confirmPaymentIntent — payment method resolution")
    class ConfirmPmResolutionTests {

        @Test
        @DisplayName("PM not found during confirm → throws PaymentMethodException")
        void pmNotFoundDuringConfirm_throws() {
            // Kills: line 351 NullReturnValsMutator on lambda$resolvePaymentMethod$5
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            stubIntentLoad(intent);
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest()))
                    .isInstanceOf(com.firstclub.payments.exception.PaymentMethodException.class);
        }

        @Test
        @DisplayName("PM from intent used when request has no paymentMethodId")
        void pmFromIntent_whenRequestHasNoPm() {
            // Kills: line 340 RemoveConditional_ELSE→false (resolvePaymentMethod fallback from intent)
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            stubIntentLoad(intent);
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(paymentMethod));

            stubConfirmSuccessPath(intent);
            stubSavePassThrough();
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            // Request has no paymentMethodId — should use intent's PM
            PaymentIntentV2ResponseDTO result = service.confirmPaymentIntent(
                    MERCHANT_ID, INTENT_ID, confirmRequest());

            assertThat(result).isNotNull();
            verify(paymentMethodRepository).findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID);
        }

        @Test
        @DisplayName("PM switch: different PM on request replaces intent PM")
        void pmSwitch_differentPm() {
            // Kills: lines 176-178 RemoveConditional (pmChanged detection)
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            stubIntentLoad(intent);
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID_2)).thenReturn(Optional.of(paymentMethod2));

            stubConfirmSuccessPath(intent);
            stubSavePassThrough();
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequestWithPm(PM_ID_2));

            // PM should have been switched
            assertThat(intent.getPaymentMethod()).isEqualTo(paymentMethod2);
        }

        @Test
        @DisplayName("same PM on request and intent → pmChanged is false, PM not re-set")
        void samePm_noSwitch() {
            // Kills: lines 177-178 RemoveConditional_IF→true (pmChanged always true)
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            stubIntentLoad(intent);
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(paymentMethod));

            stubConfirmSuccessPath(intent);
            stubSavePassThrough();
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            // Request has the SAME PM_ID as the intent already has
            service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequestWithPm(PM_ID));

            // PM should still be the same (not switched), the intent.setPaymentMethod
            // should not have been called since pmChanged=false
            assertThat(intent.getPaymentMethod()).isSameAs(paymentMethod);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // confirmPaymentIntent — Routing
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("confirmPaymentIntent — routing fallback")
    class ConfirmRoutingTests {

        @Test
        @DisplayName("routing exception with null gatewayName on request → rethrows RoutingException")
        void routingException_noFallback_rethrows() {
            // Kills: line 217 RemoveConditional_IF→true (null/blank gatewayName check)
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            stubIntentLoad(intent);
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(paymentMethod));
            when(riskDecisionService.evaluateForPaymentIntent(any())).thenReturn(allowDecision());
            when(paymentAttemptService.computeNextAttemptNumber(INTENT_ID)).thenReturn(1);
            when(paymentRoutingService.selectGatewayForAttempt(any(), any(), anyInt()))
                    .thenThrow(RoutingException.noEligibleGateway("CARD", "INR"));

            // Confirm request with NO gatewayName
            PaymentIntentConfirmRequestDTO noGatewayRequest = PaymentIntentConfirmRequestDTO.builder().build();

            assertThatThrownBy(() ->
                    service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, noGatewayRequest))
                    .isInstanceOf(RoutingException.class);
        }

        @Test
        @DisplayName("routing exception with blank gatewayName on request → rethrows RoutingException")
        void routingException_blankGatewayName_rethrows() {
            // Kills: line 217 RemoveConditional_IF→true on !isBlank() check
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            stubIntentLoad(intent);
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(paymentMethod));
            when(riskDecisionService.evaluateForPaymentIntent(any())).thenReturn(allowDecision());
            when(paymentAttemptService.computeNextAttemptNumber(INTENT_ID)).thenReturn(1);
            when(paymentRoutingService.selectGatewayForAttempt(any(), any(), anyInt()))
                    .thenThrow(RoutingException.noEligibleGateway("CARD", "INR"));

            // Blank (non-null) gatewayName
            PaymentIntentConfirmRequestDTO blankGwRequest = PaymentIntentConfirmRequestDTO.builder()
                    .gatewayName("   ").build();

            assertThatThrownBy(() ->
                    service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, blankGwRequest))
                    .isInstanceOf(RoutingException.class);
        }

        @Test
        @DisplayName("routing success with null snapshotJson → no snapshot persisted")
        void routingSuccess_nullSnapshot_notPersisted() {
            // Kills: line 230 RemoveConditional_IF→true on snapshotJson null check
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            stubIntentLoad(intent);
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(paymentMethod));
            when(riskDecisionService.evaluateForPaymentIntent(any())).thenReturn(allowDecision());

            // Routing succeeds but snapshotJson is null
            RoutingDecisionDTO routingDecision = new RoutingDecisionDTO(
                    "razorpay", 1L, false, "rule match", null);
            when(paymentRoutingService.selectGatewayForAttempt(any(), any(), anyInt()))
                    .thenReturn(routingDecision);

            PaymentAttempt attempt = buildAttempt(intent);
            when(paymentAttemptService.computeNextAttemptNumber(INTENT_ID)).thenReturn(1);
            when(paymentAttemptService.createAttempt(eq(intent), eq(1), eq("razorpay")))
                    .thenReturn(attempt);
            when(gatewayCallService.submitPayment(any(), any()))
                    .thenReturn(GatewayResult.succeeded("TXN-NS", "SUCCESS", 50L));
            when(paymentAttemptService.markCaptured(eq(1L), eq(INTENT_ID), anyString(), anyLong()))
                    .thenReturn(attempt);
            when(paymentAttemptRepository.save(any())).thenReturn(attempt);
            stubSavePassThrough();
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest());

            // No snapshot should be set on the attempt
            assertThat(attempt.getRoutingSnapshotJson()).isNull();
        }

        @Test
        @DisplayName("routing success with snapshot → snapshot persisted on attempt")
        void routingSuccess_snapshotPersisted() {
            // Kills: line 230-231 RemoveConditional, VoidMethodCall (setRoutingSnapshotJson)
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            stubIntentLoad(intent);
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(paymentMethod));
            when(riskDecisionService.evaluateForPaymentIntent(any())).thenReturn(allowDecision());

            RoutingDecisionDTO routingDecision = new RoutingDecisionDTO(
                    "razorpay", 1L, false, "rule match", "{\"snapshot\":true}");
            when(paymentRoutingService.selectGatewayForAttempt(any(), any(), anyInt()))
                    .thenReturn(routingDecision);

            PaymentAttempt attempt = buildAttempt(intent);
            when(paymentAttemptService.computeNextAttemptNumber(INTENT_ID)).thenReturn(1);
            when(paymentAttemptService.createAttempt(eq(intent), eq(1), eq("razorpay")))
                    .thenReturn(attempt);

            ArgumentCaptor<PaymentAttempt> attemptCaptor = ArgumentCaptor.forClass(PaymentAttempt.class);
            when(paymentAttemptRepository.save(attemptCaptor.capture())).thenReturn(attempt);

            when(gatewayCallService.submitPayment(any(), any()))
                    .thenReturn(GatewayResult.succeeded("TXN-002", "SUCCESS", 50L));
            when(paymentAttemptService.markCaptured(eq(1L), eq(INTENT_ID), anyString(), anyLong()))
                    .thenReturn(attempt);
            stubSavePassThrough();
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest());

            // Verify snapshot was set on first save of attempt
            assertThat(attemptCaptor.getAllValues().get(0).getRoutingSnapshotJson())
                    .isEqualTo("{\"snapshot\":true}");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // confirmPaymentIntent — Gateway result branches
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("confirmPaymentIntent — gateway result branches")
    class ConfirmGatewayResultTests {

        private PaymentAttempt stubConfirmUntilGateway(PaymentIntentV2 intent) {
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(paymentMethod));
            when(riskDecisionService.evaluateForPaymentIntent(any())).thenReturn(allowDecision());
            when(paymentRoutingService.selectGatewayForAttempt(any(), any(), anyInt()))
                    .thenThrow(RoutingException.noEligibleGateway("CARD", "INR"));
            PaymentAttempt attempt = buildAttempt(intent);
            when(paymentAttemptService.computeNextAttemptNumber(INTENT_ID)).thenReturn(1);
            when(paymentAttemptService.createAttempt(eq(intent), eq(1), anyString()))
                    .thenReturn(attempt);
            when(paymentAttemptRepository.save(any())).thenReturn(attempt);
            return attempt;
        }

        @Test
        @DisplayName("succeeded → sets lastSuccessfulAttemptId, SUCCEEDED status, gateway fields propagated")
        void succeeded_setsAllFields() {
            // Kills: lines 246-253 RemoveConditional, VoidMethodCall (setGatewayTransactionId, setGatewayReference)
            // Kills: line 262 VoidMethodCall (setLastSuccessfulAttemptId)
            // Kills: line 258 RemoveConditional_IF→true (isSucceeded check)
            // Kills: line 236 VoidMethodCall (setStatus PROCESSING)
            // Kills: line 288 NullReturn
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            stubIntentLoad(intent);
            PaymentAttempt attempt = stubConfirmUntilGateway(intent);

            GatewayResult result = GatewayResult.succeeded("TXN-003", "SUCCESS", 75L);
            when(gatewayCallService.submitPayment(any(), any())).thenReturn(result);
            when(paymentAttemptService.markCaptured(eq(1L), eq(INTENT_ID), eq("SUCCESS"), eq(75L)))
                    .thenReturn(attempt);
            stubSavePassThrough();
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            PaymentIntentV2ResponseDTO dto =
                    service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest());

            assertThat(dto).isNotNull();
            assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatusV2.SUCCEEDED);
            assertThat(intent.getLastSuccessfulAttemptId()).isEqualTo(1L);

            // Gateway fields were set on attempt
            assertThat(attempt.getGatewayTransactionId()).isEqualTo("TXN-003");
            assertThat(attempt.getGatewayReference()).isEqualTo("TXN-003");
        }

        @Test
        @DisplayName("needsReconciliation → PROCESSING status, reconciliationState=PENDING")
        void needsReconciliation_setsReconciliation() {
            // Kills: lines 266-270 RemoveConditional, VoidMethodCall (setReconciliationState, setStatus)
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            stubIntentLoad(intent);
            PaymentAttempt attempt = stubConfirmUntilGateway(intent);

            GatewayResult unknownResult = GatewayResult.unknown("TXN-UNK", "{\"status\":\"pending\"}", 200L);
            when(gatewayCallService.submitPayment(any(), any())).thenReturn(unknownResult);
            when(paymentAttemptService.markUnknown(eq(1L), eq(INTENT_ID), eq(200L)))
                    .thenReturn(attempt);
            stubSavePassThrough();
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            PaymentIntentV2ResponseDTO dto =
                    service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest());

            assertThat(dto).isNotNull();
            assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatusV2.PROCESSING);
            assertThat(intent.getReconciliationState()).isEqualTo("PENDING");

            // Gateway fields set
            assertThat(attempt.getGatewayTransactionId()).isEqualTo("TXN-UNK");
            assertThat(attempt.getResponsePayloadJson()).isEqualTo("{\"status\":\"pending\"}");
        }

        @Test
        @DisplayName("failed → FAILED status with failure category determining retriability")
        void failed_setsFailedStatus() {
            // Kills: lines 275-282 RemoveConditional, VoidMethodCall (setStatus FAILED)
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            stubIntentLoad(intent);
            PaymentAttempt attempt = stubConfirmUntilGateway(intent);

            GatewayResult failResult = GatewayResult.failed(
                    FailureCategory.ISSUER_DECLINE, "Insufficient funds", "DECLINED", 80L);
            when(gatewayCallService.submitPayment(any(), any())).thenReturn(failResult);
            when(paymentAttemptService.markFailed(eq(1L), eq(INTENT_ID),
                    eq("DECLINED"), eq("Insufficient funds"),
                    eq(FailureCategory.ISSUER_DECLINE), eq(false), eq(80L)))
                    .thenReturn(attempt);
            stubSavePassThrough();
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            PaymentIntentV2ResponseDTO dto =
                    service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest());

            assertThat(dto).isNotNull();
            assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatusV2.FAILED);
        }

        @Test
        @DisplayName("failed with retriable category → markFailed called with retriable=true")
        void failed_retriableCategory() {
            // Kills: line 275-276 RemoveConditional (failureCategory.isTypicallyRetriable)
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            stubIntentLoad(intent);
            PaymentAttempt attempt = stubConfirmUntilGateway(intent);

            GatewayResult failResult = GatewayResult.failed(
                    FailureCategory.NETWORK, "Timeout", "TIMEOUT", 5000L);
            when(gatewayCallService.submitPayment(any(), any())).thenReturn(failResult);
            when(paymentAttemptService.markFailed(eq(1L), eq(INTENT_ID),
                    eq("DECLINED"), eq("Timeout"),
                    eq(FailureCategory.NETWORK), eq(true), eq(5000L)))
                    .thenReturn(attempt);
            stubSavePassThrough();
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest());

            verify(paymentAttemptService).markFailed(1L, INTENT_ID,
                    "DECLINED", "Timeout", FailureCategory.NETWORK, true, 5000L);
        }

        @Test
        @DisplayName("failed with null failureCategory → retriable=false")
        void failed_nullCategory_notRetriable() {
            // Kills: line 275 RemoveConditional (null check on failureCategory)
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            stubIntentLoad(intent);
            PaymentAttempt attempt = stubConfirmUntilGateway(intent);

            // Create a failed result with null failureCategory
            GatewayResult failResult = new GatewayResult(
                    com.firstclub.payments.gateway.GatewayResultStatus.FAILED,
                    null, null, "ERR", "Unknown error", null, 100L, null);
            when(gatewayCallService.submitPayment(any(), any())).thenReturn(failResult);
            when(paymentAttemptService.markFailed(eq(1L), eq(INTENT_ID),
                    eq("DECLINED"), eq("Unknown error"),
                    eq(null), eq(false), eq(100L)))
                    .thenReturn(attempt);
            stubSavePassThrough();
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest());

            verify(paymentAttemptService).markFailed(1L, INTENT_ID,
                    "DECLINED", "Unknown error", null, false, 100L);
        }

        @Test
        @DisplayName("gateway latencyMs null → defaults to 0L")
        void gatewayLatencyNull_defaultsToZero() {
            // Kills: line 243 RemoveConditional (null latencyMs check)
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            stubIntentLoad(intent);
            PaymentAttempt attempt = stubConfirmUntilGateway(intent);

            // Create a gateway result with null latencyMs
            GatewayResult result = new GatewayResult(
                    com.firstclub.payments.gateway.GatewayResultStatus.SUCCEEDED,
                    "TXN-NULL-LAT", "TXN-NULL-LAT", "SUCCESS", null, null, null, null);
            when(gatewayCallService.submitPayment(any(), any())).thenReturn(result);
            when(paymentAttemptService.markCaptured(eq(1L), eq(INTENT_ID), eq("SUCCESS"), eq(0L)))
                    .thenReturn(attempt);
            stubSavePassThrough();
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest());

            // Verify markCaptured is called with 0L when gatewayResult latencyMs is null
            verify(paymentAttemptService).markCaptured(1L, INTENT_ID, "SUCCESS", 0L);
        }

        @Test
        @DisplayName("gateway returns null gatewayTransactionId → not set on attempt")
        void nullGatewayTxnId_notSet() {
            // Kills: line 246 RemoveConditional (null check)
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            stubIntentLoad(intent);
            PaymentAttempt attempt = stubConfirmUntilGateway(intent);

            // Succeeded but without txnId fields set on the record
            GatewayResult result = new GatewayResult(
                    com.firstclub.payments.gateway.GatewayResultStatus.SUCCEEDED,
                    null, null, "SUCCESS", null, null, 50L, null);
            when(gatewayCallService.submitPayment(any(), any())).thenReturn(result);
            when(paymentAttemptService.markCaptured(eq(1L), eq(INTENT_ID), eq("SUCCESS"), eq(50L)))
                    .thenReturn(attempt);
            stubSavePassThrough();
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest());

            // gatewayTransactionId should NOT be set when null
            assertThat(attempt.getGatewayTransactionId()).isNull();
            assertThat(attempt.getGatewayReference()).isNull();
        }

        @Test
        @DisplayName("succeeded with all gateway fields non-null → all fields set on attempt")
        void succeeded_allFieldsNonNull() {
            // Kills: lines 249, 252 RemoveConditional_IF→true (rawResponseJson, gatewayReference null checks)
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            stubIntentLoad(intent);
            PaymentAttempt attempt = stubConfirmUntilGateway(intent);

            // Gateway result with all fields filled
            GatewayResult result = new GatewayResult(
                    com.firstclub.payments.gateway.GatewayResultStatus.SUCCEEDED,
                    "TXN-FULL", "REF-FULL", "SUCCESS", null,
                    "{\"response\":\"ok\"}", 42L, null);
            when(gatewayCallService.submitPayment(any(), any())).thenReturn(result);
            when(paymentAttemptService.markCaptured(eq(1L), eq(INTENT_ID), eq("SUCCESS"), eq(42L)))
                    .thenReturn(attempt);
            stubSavePassThrough();
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest());

            assertThat(attempt.getGatewayTransactionId()).isEqualTo("TXN-FULL");
            assertThat(attempt.getGatewayReference()).isEqualTo("REF-FULL");
            assertThat(attempt.getResponsePayloadJson()).isEqualTo("{\"response\":\"ok\"}");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // cancelPaymentIntent
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("cancelPaymentIntent — mutation killers")
    class CancelMutantKillers {

        @Test
        @DisplayName("cancel returns non-null DTO")
        void cancelReturnsNonNull() {
            // Kills: line 314 NullReturnValsMutator
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_PAYMENT_METHOD, null);
            stubIntentLoad(intent);
            stubSavePassThrough();
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            PaymentIntentV2ResponseDTO result = service.cancelPaymentIntent(MERCHANT_ID, INTENT_ID);

            assertThat(result).isNotNull();
            assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatusV2.CANCELLED);
        }

        @Test
        @DisplayName("REQUIRES_ACTION → can be cancelled")
        void requiresAction_canBeCancelled() {
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_ACTION, paymentMethod);
            stubIntentLoad(intent);
            stubSavePassThrough();
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            PaymentIntentV2ResponseDTO result = service.cancelPaymentIntent(MERCHANT_ID, INTENT_ID);

            assertThat(result).isNotNull();
            assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatusV2.CANCELLED);
        }

        @Test
        @DisplayName("PROCESSING → cannot be cancelled")
        void processing_cannotBeCancelled() {
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.PROCESSING, paymentMethod);
            stubIntentLoad(intent);

            assertThatThrownBy(() -> service.cancelPaymentIntent(MERCHANT_ID, INTENT_ID))
                    .isInstanceOf(PaymentIntentException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_PAYMENT_INTENT_TRANSITION");
        }

        @Test
        @DisplayName("FAILED → cannot be cancelled")
        void failed_cannotBeCancelled() {
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.FAILED, paymentMethod);
            stubIntentLoad(intent);

            assertThatThrownBy(() -> service.cancelPaymentIntent(MERCHANT_ID, INTENT_ID))
                    .isInstanceOf(PaymentIntentException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_PAYMENT_INTENT_TRANSITION");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // listAttempts
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("listAttempts — no-coverage killers")
    class ListAttemptsTests {

        @Test
        @DisplayName("returns mapped DTOs for valid merchant+intent")
        void returnsMappedDtos() {
            // Kills: line 323 EmptyObjectReturnValsMutator
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            stubIntentLoad(intent);

            PaymentAttempt attempt = PaymentAttempt.builder()
                    .id(1L).paymentIntent(intent).attemptNumber(1)
                    .gatewayName("razorpay").status(PaymentAttemptStatus.STARTED).build();
            when(paymentAttemptRepository.findByPaymentIntentIdOrderByAttemptNumberAsc(INTENT_ID))
                    .thenReturn(List.of(attempt));

            PaymentAttemptResponseDTO dto = PaymentAttemptResponseDTO.builder()
                    .id(1L).paymentIntentId(INTENT_ID).build();
            when(paymentAttemptMapper.toResponseDTO(attempt)).thenReturn(dto);

            List<PaymentAttemptResponseDTO> result = service.listAttempts(MERCHANT_ID, INTENT_ID);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("intent not found → throws PAYMENT_INTENT_NOT_FOUND")
        void notFound_throws() {
            when(paymentIntentV2Repository.findByMerchantIdAndId(MERCHANT_ID, INTENT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.listAttempts(MERCHANT_ID, INTENT_ID))
                    .isInstanceOf(PaymentIntentException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "PAYMENT_INTENT_NOT_FOUND");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // reconcileGatewayStatus
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("reconcileGatewayStatus — no-coverage killers")
    class ReconcileTests {

        @Test
        @DisplayName("returns non-null DTO after delegating to reconciler")
        void returnsNonNull() {
            // Kills: line 298 NullReturnValsMutator
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.PROCESSING, paymentMethod);
            when(paymentIntentV2Repository.findByMerchantIdAndId(MERCHANT_ID, INTENT_ID))
                    .thenReturn(Optional.of(intent));
            when(paymentOutcomeReconciler.reconcileIntent(INTENT_ID)).thenReturn(2);
            when(paymentIntentV2Mapper.toResponseDTO(intent)).thenReturn(dummyResponse);

            PaymentIntentV2ResponseDTO result =
                    service.reconcileGatewayStatus(MERCHANT_ID, INTENT_ID);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(INTENT_ID);
            verify(paymentOutcomeReconciler).reconcileIntent(INTENT_ID);
        }

        @Test
        @DisplayName("throws when intent not found for merchant")
        void notFound_throws() {
            when(paymentIntentV2Repository.findByMerchantIdAndId(MERCHANT_ID, INTENT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.reconcileGatewayStatus(MERCHANT_ID, INTENT_ID))
                    .isInstanceOf(PaymentIntentException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "PAYMENT_INTENT_NOT_FOUND");
        }
    }
}
