package com.firstclub.payments.service;

import com.firstclub.customer.entity.Customer;
import com.firstclub.customer.entity.CustomerStatus;
import com.firstclub.customer.repository.CustomerRepository;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.repository.MerchantAccountRepository;
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
import com.firstclub.payments.mapper.PaymentAttemptMapper;
import com.firstclub.payments.mapper.PaymentIntentV2Mapper;
import com.firstclub.payments.repository.PaymentAttemptRepository;
import com.firstclub.payments.repository.PaymentIntentV2Repository;
import com.firstclub.payments.repository.PaymentMethodRepository;
import com.firstclub.payments.routing.exception.RoutingException;
import com.firstclub.payments.routing.service.PaymentRoutingService;
import com.firstclub.payments.service.impl.PaymentIntentV2ServiceImpl;
import com.firstclub.payments.gateway.GatewayResult;
import com.firstclub.payments.gateway.PaymentGatewayCallService;
import com.firstclub.payments.recovery.PaymentOutcomeReconciler;
import com.firstclub.risk.dto.RiskDecisionResponseDTO;
import com.firstclub.risk.entity.RiskAction;
import com.firstclub.risk.service.RiskDecisionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentIntentV2ServiceImpl Unit Tests")
class PaymentIntentV2ServiceTest {

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

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static final Long MERCHANT_ID = 1L;
    private static final Long CUSTOMER_ID = 5L;
    private static final Long PM_ID       = 10L;
    private static final Long INTENT_ID   = 100L;

    private MerchantAccount merchant;
    private Customer customer;
    private PaymentMethod paymentMethod;
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

        dummyResponse = PaymentIntentV2ResponseDTO.builder()
                .id(INTENT_ID).merchantId(MERCHANT_ID).customerId(CUSTOMER_ID).build();
    }

    // ── Helper to build a payment intent ─────────────────────────────────────

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

    // ── Create ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createPaymentIntent")
    class CreateTests {

        private PaymentIntentCreateRequestDTO buildCreateRequest(Long pmId) {
            return PaymentIntentCreateRequestDTO.builder()
                    .customerId(CUSTOMER_ID)
                    .amount(new BigDecimal("999.00"))
                    .currency("INR")
                    .captureMode(CaptureMode.AUTO)
                    .paymentMethodId(pmId)
                    .build();
        }

        @Test
        @DisplayName("no payment method -> status is REQUIRES_PAYMENT_METHOD")
        void noPaymentMethod_requiresPaymentMethod() {
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
            when(customerRepository.findByMerchantIdAndId(MERCHANT_ID, CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));
            when(paymentIntentV2Repository.findByIdempotencyKeyAndMerchantId(any(), any()))
                    .thenReturn(Optional.empty());
            when(paymentIntentV2Repository.save(any())).thenAnswer(inv -> {
                PaymentIntentV2 pi = inv.getArgument(0);
                pi.setId(INTENT_ID);
                return pi;
            });
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            service.createPaymentIntent(MERCHANT_ID, "key-1",
                    buildCreateRequest(null));

            verify(paymentIntentV2Repository).save(argThat(pi ->
                    pi.getStatus() == PaymentIntentStatusV2.REQUIRES_PAYMENT_METHOD));
        }

        @Test
        @DisplayName("with payment method -> status is REQUIRES_CONFIRMATION")
        void withPaymentMethod_requiresConfirmation() {
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
            when(customerRepository.findByMerchantIdAndId(MERCHANT_ID, CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(paymentMethod));
            when(paymentIntentV2Repository.findByIdempotencyKeyAndMerchantId(any(), any()))
                    .thenReturn(Optional.empty());
            when(paymentIntentV2Repository.save(any())).thenAnswer(inv -> {
                PaymentIntentV2 pi = inv.getArgument(0);
                pi.setId(INTENT_ID);
                return pi;
            });
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            service.createPaymentIntent(MERCHANT_ID, "key-2",
                    buildCreateRequest(PM_ID));

            verify(paymentIntentV2Repository).save(argThat(pi ->
                    pi.getStatus() == PaymentIntentStatusV2.REQUIRES_CONFIRMATION));
        }

        @Test
        @DisplayName("duplicate idempotency key -> returns existing without saving again")
        void idempotentKey_returnsExisting() {
            PaymentIntentV2 existing = buildIntent(
                    PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            when(paymentIntentV2Repository.findByIdempotencyKeyAndMerchantId("dup-key", MERCHANT_ID))
                    .thenReturn(Optional.of(existing));
            when(paymentIntentV2Mapper.toResponseDTO(existing)).thenReturn(dummyResponse);

            PaymentIntentV2ResponseDTO result =
                    service.createPaymentIntent(MERCHANT_ID, "dup-key", buildCreateRequest(null));

            assertThat(result).isNotNull();
            verify(paymentIntentV2Repository, never()).save(any());
        }
    }

    // ── Confirm ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("confirmPaymentIntent")
    class ConfirmTests {

        private PaymentIntentConfirmRequestDTO confirmRequest() {
            return PaymentIntentConfirmRequestDTO.builder()
                    .gatewayName("razorpay")
                    .build();
        }

        private void stubNextAttemptAndCreate(PaymentIntentV2 intent) {
            // Stub routing service to throw so the code falls back to request.gatewayName
            when(paymentRoutingService.selectGatewayForAttempt(any(), any(), anyInt()))
                    .thenThrow(RoutingException.noEligibleGateway("CARD", "INR"));
            PaymentAttempt attempt = PaymentAttempt.builder()
                    .id(1L).paymentIntent(intent).attemptNumber(1)
                    .gatewayName("razorpay").status(PaymentAttemptStatus.STARTED).build();
            when(paymentAttemptService.computeNextAttemptNumber(INTENT_ID)).thenReturn(1);
            when(paymentAttemptService.createAttempt(eq(intent), eq(1), anyString()))
                    .thenReturn(attempt);
            // Phase 8: gateway call service returns a SUCCEEDED result
            when(gatewayCallService.submitPayment(any(), any()))
                    .thenReturn(GatewayResult.succeeded("TXN-TEST-001", "SUCCESS", 100L));
            PaymentAttempt succeededAttempt = PaymentAttempt.builder()
                    .id(1L).paymentIntent(intent).attemptNumber(1)
                    .status(PaymentAttemptStatus.CAPTURED).build();
            when(paymentAttemptService.markCaptured(eq(1L), eq(INTENT_ID), anyString(), anyLong()))
                    .thenReturn(succeededAttempt);
            when(paymentAttemptRepository.save(any())).thenReturn(succeededAttempt);
        }

        @Test
        @DisplayName("success — intent transitions to SUCCEEDED")
        void success_intentSucceeds() {
            PaymentIntentV2 intent = buildIntent(
                    PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            when(paymentIntentV2Repository.findByMerchantIdAndId(MERCHANT_ID, INTENT_ID))
                    .thenReturn(Optional.of(intent));
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(paymentMethod));
            when(riskDecisionService.evaluateForPaymentIntent(any()))
                    .thenReturn(new RiskDecisionResponseDTO(1L, MERCHANT_ID, INTENT_ID, CUSTOMER_ID,
                            0, RiskAction.ALLOW, "[]", null));
            stubNextAttemptAndCreate(intent);
            when(paymentIntentV2Repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest());

            assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatusV2.SUCCEEDED);
        }

        @Test
        @DisplayName("already SUCCEEDED — returns snapshot idempotently")
        void alreadySucceeded_returnsSnapshot() {
            PaymentIntentV2 intent = buildIntent(
                    PaymentIntentStatusV2.SUCCEEDED, paymentMethod);
            when(paymentIntentV2Repository.findByMerchantIdAndId(MERCHANT_ID, INTENT_ID))
                    .thenReturn(Optional.of(intent));
            when(paymentIntentV2Mapper.toResponseDTO(intent)).thenReturn(dummyResponse);

            PaymentIntentV2ResponseDTO result =
                    service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest());

            assertThat(result).isNotNull();
            verify(paymentAttemptService, never()).createAttempt(any(), anyInt(), any());
        }

        @Test
        @DisplayName("no payment method on intent or request -> throws MISSING_PAYMENT_METHOD")
        void noPaymentMethod_throws() {
            PaymentIntentV2 intent = buildIntent(
                    PaymentIntentStatusV2.REQUIRES_PAYMENT_METHOD, null);
            when(paymentIntentV2Repository.findByMerchantIdAndId(MERCHANT_ID, INTENT_ID))
                    .thenReturn(Optional.of(intent));

            assertThatThrownBy(() ->
                    service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest()))
                    .isInstanceOf(PaymentIntentException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "MISSING_PAYMENT_METHOD");
        }

        @Test
        @DisplayName("FAILED intent with non-retriable last attempt -> throws NON_RETRIABLE_FAILURE")
        void nonRetriable_throws() {
            PaymentIntentV2 intent = buildIntent(PaymentIntentStatusV2.FAILED, paymentMethod);
            PaymentAttempt lastAttempt = PaymentAttempt.builder()
                    .id(1L).paymentIntent(intent).attemptNumber(1)
                    .status(PaymentAttemptStatus.FAILED).retriable(false)
                    .failureCategory(FailureCategory.RISK_BLOCK).build();
            when(paymentIntentV2Repository.findByMerchantIdAndId(MERCHANT_ID, INTENT_ID))
                    .thenReturn(Optional.of(intent));
            when(paymentAttemptRepository.findTopByPaymentIntentIdOrderByAttemptNumberDesc(INTENT_ID))
                    .thenReturn(Optional.of(lastAttempt));

            assertThatThrownBy(() ->
                    service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest()))
                    .isInstanceOf(PaymentIntentException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "NON_RETRIABLE_FAILURE");
        }

        @Test
        @DisplayName("CANCELLED intent -> throws INVALID_PAYMENT_INTENT_TRANSITION")
        void cancelled_throws() {
            PaymentIntentV2 intent = buildIntent(
                    PaymentIntentStatusV2.CANCELLED, paymentMethod);
            when(paymentIntentV2Repository.findByMerchantIdAndId(MERCHANT_ID, INTENT_ID))
                    .thenReturn(Optional.of(intent));

            assertThatThrownBy(() ->
                    service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest()))
                    .isInstanceOf(PaymentIntentException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_PAYMENT_INTENT_TRANSITION");
        }

        @Test
        @DisplayName("gateway failure — actual responseCode from GatewayResult is forwarded to markFailed")
        void gatewayFailure_propagatesActualResponseCode() {
            // Regression test: previously "DECLINED" was hardcoded regardless of gateway response code.
            PaymentIntentV2 intent = buildIntent(
                    PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            when(paymentIntentV2Repository.findByMerchantIdAndId(MERCHANT_ID, INTENT_ID))
                    .thenReturn(Optional.of(intent));
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(paymentMethod));
            when(riskDecisionService.evaluateForPaymentIntent(any()))
                    .thenReturn(new RiskDecisionResponseDTO(1L, MERCHANT_ID, INTENT_ID, CUSTOMER_ID,
                            0, RiskAction.ALLOW, "[]", null));
            when(paymentRoutingService.selectGatewayForAttempt(any(), any(), anyInt()))
                    .thenThrow(RoutingException.noEligibleGateway("CARD", "INR"));
            PaymentAttempt attempt = PaymentAttempt.builder()
                    .id(1L).paymentIntent(intent).attemptNumber(1)
                    .gatewayName("razorpay").status(PaymentAttemptStatus.STARTED).build();
            when(paymentAttemptService.computeNextAttemptNumber(INTENT_ID)).thenReturn(1);
            when(paymentAttemptService.createAttempt(eq(intent), eq(1), anyString()))
                    .thenReturn(attempt);
            when(paymentAttemptRepository.save(any())).thenReturn(attempt);

            // Gateway returns a specific error code (not "DECLINED")
            GatewayResult declineResult = GatewayResult.failed(
                    FailureCategory.ISSUER_DECLINE, "Do not honor", "DO_NOT_HONOR", 120L);
            when(gatewayCallService.submitPayment(any(), any())).thenReturn(declineResult);
            when(paymentAttemptService.markFailed(anyLong(), anyLong(), anyString(),
                    anyString(), any(), anyBoolean(), anyLong())).thenReturn(attempt);
            when(paymentIntentV2Repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            service.confirmPaymentIntent(MERCHANT_ID, INTENT_ID, confirmRequest());

            // Must forward the actual gateway response code, NOT a hardcoded "DECLINED"
            verify(paymentAttemptService).markFailed(
                    eq(1L), eq(INTENT_ID),
                    eq("DO_NOT_HONOR"), eq("Do not honor"),
                    eq(FailureCategory.ISSUER_DECLINE), anyBoolean(), eq(120L));
        }
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cancelPaymentIntent")
    class CancelTests {

        @Test
        @DisplayName("REQUIRES_CONFIRMATION -> CANCELLED")
        void requiresConfirmation_cancelled() {
            PaymentIntentV2 intent = buildIntent(
                    PaymentIntentStatusV2.REQUIRES_CONFIRMATION, paymentMethod);
            when(paymentIntentV2Repository.findByMerchantIdAndId(MERCHANT_ID, INTENT_ID))
                    .thenReturn(Optional.of(intent));
            when(paymentIntentV2Repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(paymentIntentV2Mapper.toResponseDTO(any())).thenReturn(dummyResponse);

            service.cancelPaymentIntent(MERCHANT_ID, INTENT_ID);

            assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatusV2.CANCELLED);
        }

        @Test
        @DisplayName("already SUCCEEDED -> throws INVALID_PAYMENT_INTENT_TRANSITION")
        void alreadySucceeded_throws() {
            PaymentIntentV2 intent = buildIntent(
                    PaymentIntentStatusV2.SUCCEEDED, paymentMethod);
            when(paymentIntentV2Repository.findByMerchantIdAndId(MERCHANT_ID, INTENT_ID))
                    .thenReturn(Optional.of(intent));

            assertThatThrownBy(() -> service.cancelPaymentIntent(MERCHANT_ID, INTENT_ID))
                    .isInstanceOf(PaymentIntentException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_PAYMENT_INTENT_TRANSITION");
        }
    }
}
