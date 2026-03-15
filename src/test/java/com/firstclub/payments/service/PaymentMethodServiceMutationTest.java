package com.firstclub.payments.service;

import com.firstclub.customer.entity.Customer;
import com.firstclub.customer.entity.CustomerStatus;
import com.firstclub.customer.exception.CustomerException;
import com.firstclub.customer.repository.CustomerRepository;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.exception.MerchantException;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import com.firstclub.payments.dto.PaymentMethodCreateRequestDTO;
import com.firstclub.payments.dto.PaymentMethodResponseDTO;
import com.firstclub.payments.entity.PaymentMethod;
import com.firstclub.payments.entity.PaymentMethodStatus;
import com.firstclub.payments.entity.PaymentMethodType;
import com.firstclub.payments.mapper.PaymentMethodMapper;
import com.firstclub.payments.repository.PaymentMethodRepository;
import com.firstclub.payments.service.impl.PaymentMethodServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Mutation-killing tests for {@link PaymentMethodServiceImpl}.
 *
 * <p>Targets surviving and no-coverage mutants identified by PIT baseline:
 * <ul>
 *   <li>createPaymentMethod — L49/50/51 setMerchant/setCustomer/setStatus removed,
 *       L54/55 existingMethods/shouldBeDefault conditions,
 *       L57/59/61 default-flag assignment</li>
 *   <li>revokePaymentMethod — L122 null return value</li>
 *   <li>setDefaultPaymentMethod — L97 already-default idempotency guard</li>
 *   <li>listCustomerPaymentMethods — L75/77/79 no-coverage mutants</li>
 *   <li>getDefaultPaymentMethod — L130/132/134 no-coverage mutants</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentMethodServiceImpl — Mutation-killing tests")
class PaymentMethodServiceMutationTest {

    @Mock private PaymentMethodRepository paymentMethodRepository;
    @Mock private MerchantAccountRepository merchantAccountRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private PaymentMethodMapper paymentMethodMapper;

    @InjectMocks
    private PaymentMethodServiceImpl service;

    private static final Long MERCHANT_ID = 1L;
    private static final Long CUSTOMER_ID = 5L;
    private static final Long PM_ID = 10L;

    private MerchantAccount merchant;
    private Customer customer;
    private PaymentMethodResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        merchant = MerchantAccount.builder()
                .id(MERCHANT_ID)
                .merchantCode("M1")
                .legalName("Test Merchant")
                .status(MerchantStatus.ACTIVE)
                .build();

        customer = Customer.builder()
                .id(CUSTOMER_ID)
                .merchant(merchant)
                .email("c@test.com")
                .status(CustomerStatus.ACTIVE)
                .build();

        responseDTO = PaymentMethodResponseDTO.builder()
                .id(PM_ID)
                .merchantId(MERCHANT_ID)
                .customerId(CUSTOMER_ID)
                .methodType(PaymentMethodType.CARD)
                .provider("razorpay")
                .status(PaymentMethodStatus.ACTIVE)
                .build();
    }

    // ── createPaymentMethod — entity field assignments (L49, L50, L51) ────────

    @Nested
    @DisplayName("createPaymentMethod — entity field mutations")
    class CreateEntityFieldTests {

        @Test
        @DisplayName("saved entity has merchant, customer and ACTIVE status set")
        void savedEntityHasMerchantCustomerAndActiveStatus() {
            PaymentMethodCreateRequestDTO request = PaymentMethodCreateRequestDTO.builder()
                    .methodType(PaymentMethodType.CARD)
                    .providerToken("tok_001")
                    .provider("razorpay")
                    .makeDefault(false)
                    .build();

            // Entity returned by mapper has status explicitly set to non-ACTIVE.
            // This ensures the service's setStatus(ACTIVE) call is observable.
            PaymentMethod freshEntity = new PaymentMethod();
            freshEntity.setMethodType(PaymentMethodType.CARD);
            freshEntity.setProviderToken("tok_001");
            freshEntity.setProvider("razorpay");
            freshEntity.setStatus(null);

            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
            when(customerRepository.findByMerchantIdAndId(MERCHANT_ID, CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));
            when(paymentMethodRepository.existsByProviderAndProviderToken(anyString(), anyString()))
                    .thenReturn(false);
            when(paymentMethodRepository.findByMerchantIdAndCustomerId(MERCHANT_ID, CUSTOMER_ID))
                    .thenReturn(Collections.emptyList());
            when(paymentMethodMapper.toEntity(request)).thenReturn(freshEntity);
            when(paymentMethodRepository.findByCustomerIdAndIsDefaultTrue(CUSTOMER_ID))
                    .thenReturn(Optional.empty());
            when(paymentMethodRepository.save(any(PaymentMethod.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(paymentMethodMapper.toResponseDTO(any(PaymentMethod.class))).thenReturn(responseDTO);

            service.createPaymentMethod(MERCHANT_ID, CUSTOMER_ID, request);

            ArgumentCaptor<PaymentMethod> captor = ArgumentCaptor.forClass(PaymentMethod.class);
            verify(paymentMethodRepository).save(captor.capture());
            PaymentMethod saved = captor.getValue();

            // Kills L49: removed call to setMerchant
            assertThat(saved.getMerchant()).isSameAs(merchant);
            // Kills L50: removed call to setCustomer
            assertThat(saved.getCustomer()).isSameAs(customer);
            // Kills L51: removed call to setStatus
            assertThat(saved.getStatus()).isEqualTo(PaymentMethodStatus.ACTIVE);
        }
    }

    // ── createPaymentMethod — default flag logic (L54, L55, L57, L59, L61) ───

    @Nested
    @DisplayName("createPaymentMethod — default flag mutations")
    class CreateDefaultFlagTests {

        @Test
        @DisplayName("second method with makeDefault=false → isDefault=false")
        void secondMethodNotDefault() {
            PaymentMethodCreateRequestDTO request = PaymentMethodCreateRequestDTO.builder()
                    .methodType(PaymentMethodType.UPI)
                    .providerToken("upi_tok_002")
                    .provider("razorpay")
                    .makeDefault(false)
                    .build();

            PaymentMethod existing = PaymentMethod.builder()
                    .id(99L).merchant(merchant).customer(customer)
                    .methodType(PaymentMethodType.CARD)
                    .providerToken("tok_existing")
                    .provider("razorpay")
                    .status(PaymentMethodStatus.ACTIVE)
                    .build();

            // Entity returned by mapper starts with isDefault=true.
            // This ensures the service's setDefault(false) in the else branch is observable.
            PaymentMethod freshEntity = new PaymentMethod();
            freshEntity.setMethodType(PaymentMethodType.UPI);
            freshEntity.setProviderToken("upi_tok_002");
            freshEntity.setProvider("razorpay");
            freshEntity.setDefault(true);

            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
            when(customerRepository.findByMerchantIdAndId(MERCHANT_ID, CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));
            when(paymentMethodRepository.existsByProviderAndProviderToken(anyString(), anyString()))
                    .thenReturn(false);
            when(paymentMethodRepository.findByMerchantIdAndCustomerId(MERCHANT_ID, CUSTOMER_ID))
                    .thenReturn(List.of(existing));
            when(paymentMethodMapper.toEntity(request)).thenReturn(freshEntity);
            when(paymentMethodRepository.save(any(PaymentMethod.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(paymentMethodMapper.toResponseDTO(any(PaymentMethod.class))).thenReturn(responseDTO);

            service.createPaymentMethod(MERCHANT_ID, CUSTOMER_ID, request);

            ArgumentCaptor<PaymentMethod> captor = ArgumentCaptor.forClass(PaymentMethod.class);
            verify(paymentMethodRepository).save(captor.capture());
            PaymentMethod saved = captor.getValue();

            // Kills L54 (existingMethods condition mutated),
            //   L55 (shouldBeDefault OR conditions mutated),
            //   L57 (if shouldBeDefault → always true),
            //   L61 (setDefault(false) removed)
            assertThat(saved.isDefault()).isFalse();
            // clearCurrentDefault must NOT have been called
            verify(paymentMethodRepository, never()).findByCustomerIdAndIsDefaultTrue(any());
        }

        @Test
        @DisplayName("first method with makeDefault=false → auto-default, isDefault=true")
        void firstMethodAutoDefaultSetsFlag() {
            PaymentMethodCreateRequestDTO request = PaymentMethodCreateRequestDTO.builder()
                    .methodType(PaymentMethodType.CARD)
                    .providerToken("tok_first")
                    .provider("razorpay")
                    .makeDefault(false)
                    .build();

            PaymentMethod freshEntity = PaymentMethod.builder()
                    .methodType(PaymentMethodType.CARD)
                    .providerToken("tok_first")
                    .provider("razorpay")
                    .build();

            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
            when(customerRepository.findByMerchantIdAndId(MERCHANT_ID, CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));
            when(paymentMethodRepository.existsByProviderAndProviderToken(anyString(), anyString()))
                    .thenReturn(false);
            when(paymentMethodRepository.findByMerchantIdAndCustomerId(MERCHANT_ID, CUSTOMER_ID))
                    .thenReturn(Collections.emptyList());
            when(paymentMethodMapper.toEntity(request)).thenReturn(freshEntity);
            when(paymentMethodRepository.findByCustomerIdAndIsDefaultTrue(CUSTOMER_ID))
                    .thenReturn(Optional.empty());
            when(paymentMethodRepository.save(any(PaymentMethod.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(paymentMethodMapper.toResponseDTO(any(PaymentMethod.class))).thenReturn(responseDTO);

            service.createPaymentMethod(MERCHANT_ID, CUSTOMER_ID, request);

            ArgumentCaptor<PaymentMethod> captor = ArgumentCaptor.forClass(PaymentMethod.class);
            verify(paymentMethodRepository).save(captor.capture());
            PaymentMethod saved = captor.getValue();

            // Kills L55 (EQUAL_ELSE on !existingMethods),
            //   L59 (setDefault(true) removed)
            assertThat(saved.isDefault()).isTrue();
        }
    }

    // ── revokePaymentMethod — return value (L122) ────────────────────────────

    @Nested
    @DisplayName("revokePaymentMethod — return value mutations")
    class RevokeReturnValueTests {

        @Test
        @DisplayName("revokePaymentMethod returns non-null mapped response")
        void returnsNonNullResponse() {
            PaymentMethod pm = PaymentMethod.builder()
                    .id(PM_ID).merchant(merchant).customer(customer)
                    .methodType(PaymentMethodType.CARD)
                    .providerToken("tok_001").provider("razorpay")
                    .status(PaymentMethodStatus.ACTIVE)
                    .build();

            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(pm));
            when(paymentMethodRepository.save(pm)).thenReturn(pm);
            when(paymentMethodMapper.toResponseDTO(pm)).thenReturn(responseDTO);

            PaymentMethodResponseDTO result =
                    service.revokePaymentMethod(MERCHANT_ID, CUSTOMER_ID, PM_ID);

            // Kills L122: replaced return value with null
            assertThat(result).isNotNull();
            assertThat(result).isEqualTo(responseDTO);
        }
    }

    // ── setDefaultPaymentMethod — already-default idempotency (L97) ──────────

    @Nested
    @DisplayName("setDefaultPaymentMethod — already-default guard")
    class SetDefaultAlreadyDefaultTests {

        @Test
        @DisplayName("already-default method → no save, no clearCurrentDefault")
        void alreadyDefaultSkipsSave() {
            PaymentMethod pm = PaymentMethod.builder()
                    .id(PM_ID).merchant(merchant).customer(customer)
                    .methodType(PaymentMethodType.CARD)
                    .providerToken("tok_001").provider("razorpay")
                    .status(PaymentMethodStatus.ACTIVE)
                    .build();
            pm.setDefault(true);

            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(pm));
            when(paymentMethodMapper.toResponseDTO(pm)).thenReturn(responseDTO);

            service.setDefaultPaymentMethod(MERCHANT_ID, CUSTOMER_ID, PM_ID);

            // Kills L97: if (!pm.isDefault()) → replaced with if (true)
            // When already default, no save and no clearCurrentDefault should occur
            verify(paymentMethodRepository, never()).save(any());
            verify(paymentMethodRepository, never()).findByCustomerIdAndIsDefaultTrue(any());
        }
    }

    // ── listCustomerPaymentMethods — no-coverage mutants (L75, L77, L79) ─────

    @Nested
    @DisplayName("listCustomerPaymentMethods — no-coverage mutants")
    class ListPaymentMethodsTests {

        @Test
        @DisplayName("returns mapped payment methods for valid merchant+customer")
        void returnsMappedMethods() {
            PaymentMethod pm = PaymentMethod.builder()
                    .id(PM_ID).merchant(merchant).customer(customer)
                    .methodType(PaymentMethodType.CARD)
                    .providerToken("tok_001").provider("razorpay")
                    .status(PaymentMethodStatus.ACTIVE)
                    .build();

            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
            when(customerRepository.findByMerchantIdAndId(MERCHANT_ID, CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));
            when(paymentMethodRepository.findByMerchantIdAndCustomerId(MERCHANT_ID, CUSTOMER_ID))
                    .thenReturn(List.of(pm));
            when(paymentMethodMapper.toResponseDTO(pm)).thenReturn(responseDTO);

            List<PaymentMethodResponseDTO> result =
                    service.listCustomerPaymentMethods(MERCHANT_ID, CUSTOMER_ID);

            // Kills L79: replaced return value with Collections.emptyList
            assertThat(result).hasSize(1);
            assertThat(result.get(0)).isEqualTo(responseDTO);
        }

        @Test
        @DisplayName("unknown merchant → MerchantException")
        void unknownMerchant() {
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.empty());

            // Kills L75: lambda NullReturnValsMutator
            assertThatThrownBy(
                    () -> service.listCustomerPaymentMethods(MERCHANT_ID, CUSTOMER_ID))
                    .isInstanceOf(MerchantException.class);
        }

        @Test
        @DisplayName("unknown customer → CustomerException")
        void unknownCustomer() {
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
            when(customerRepository.findByMerchantIdAndId(MERCHANT_ID, CUSTOMER_ID))
                    .thenReturn(Optional.empty());

            // Kills L77: lambda NullReturnValsMutator
            assertThatThrownBy(
                    () -> service.listCustomerPaymentMethods(MERCHANT_ID, CUSTOMER_ID))
                    .isInstanceOf(CustomerException.class);
        }
    }

    // ── getDefaultPaymentMethod — no-coverage mutants (L130, L132, L134) ─────

    @Nested
    @DisplayName("getDefaultPaymentMethod — no-coverage mutants")
    class GetDefaultPaymentMethodTests {

        @Test
        @DisplayName("returns present Optional when default exists")
        void returnsDefaultWhenPresent() {
            PaymentMethod pm = PaymentMethod.builder()
                    .id(PM_ID).merchant(merchant).customer(customer)
                    .methodType(PaymentMethodType.CARD)
                    .providerToken("tok_001").provider("razorpay")
                    .status(PaymentMethodStatus.ACTIVE)
                    .build();
            pm.setDefault(true);

            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
            when(customerRepository.findByMerchantIdAndId(MERCHANT_ID, CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));
            when(paymentMethodRepository.findByCustomerIdAndIsDefaultTrue(CUSTOMER_ID))
                    .thenReturn(Optional.of(pm));
            when(paymentMethodMapper.toResponseDTO(pm)).thenReturn(responseDTO);

            Optional<PaymentMethodResponseDTO> result =
                    service.getDefaultPaymentMethod(MERCHANT_ID, CUSTOMER_ID);

            // Kills L134: replaced return value with Optional.empty
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(responseDTO);
        }

        @Test
        @DisplayName("returns empty Optional when no default exists")
        void returnsEmptyWhenNoDefault() {
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
            when(customerRepository.findByMerchantIdAndId(MERCHANT_ID, CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));
            when(paymentMethodRepository.findByCustomerIdAndIsDefaultTrue(CUSTOMER_ID))
                    .thenReturn(Optional.empty());

            Optional<PaymentMethodResponseDTO> result =
                    service.getDefaultPaymentMethod(MERCHANT_ID, CUSTOMER_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("unknown merchant → MerchantException")
        void unknownMerchant() {
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.empty());

            // Kills L130: lambda NullReturnValsMutator
            assertThatThrownBy(
                    () -> service.getDefaultPaymentMethod(MERCHANT_ID, CUSTOMER_ID))
                    .isInstanceOf(MerchantException.class);
        }

        @Test
        @DisplayName("unknown customer → CustomerException")
        void unknownCustomer() {
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
            when(customerRepository.findByMerchantIdAndId(MERCHANT_ID, CUSTOMER_ID))
                    .thenReturn(Optional.empty());

            // Kills L132: lambda NullReturnValsMutator
            assertThatThrownBy(
                    () -> service.getDefaultPaymentMethod(MERCHANT_ID, CUSTOMER_ID))
                    .isInstanceOf(CustomerException.class);
        }
    }
}
