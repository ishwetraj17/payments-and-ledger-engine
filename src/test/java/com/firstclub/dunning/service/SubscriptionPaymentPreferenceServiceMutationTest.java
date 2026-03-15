package com.firstclub.dunning.service;

import com.firstclub.dunning.dto.SubscriptionPaymentPreferenceRequestDTO;
import com.firstclub.dunning.dto.SubscriptionPaymentPreferenceResponseDTO;
import com.firstclub.dunning.entity.SubscriptionPaymentPreference;
import com.firstclub.dunning.repository.SubscriptionPaymentPreferenceRepository;
import com.firstclub.dunning.service.impl.SubscriptionPaymentPreferenceServiceImpl;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.payments.entity.PaymentMethod;
import com.firstclub.payments.entity.PaymentMethodStatus;
import com.firstclub.payments.repository.PaymentMethodRepository;
import com.firstclub.subscription.repository.SubscriptionV2Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Mutation-killing tests for {@link SubscriptionPaymentPreferenceServiceImpl}.
 *
 * <p>Targets the following PIT-identified surviving / no-coverage mutants:
 * <ol>
 *   <li>Line 63 — VoidMethodCallMutator: removed call to setRetryOrderJson (SURVIVED)</li>
 *   <li>Line 112 — RemoveConditionalMutator_EQUAL_ELSE: status != ACTIVE replaced with false (SURVIVED)</li>
 *   <li>Line 88 — NullReturnValsMutator: lambda in getPreferencesForSubscription subscription-not-found (NO_COVERAGE)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionPaymentPreferenceService Mutation Tests")
class SubscriptionPaymentPreferenceServiceMutationTest {

    @Mock private SubscriptionPaymentPreferenceRepository preferenceRepository;
    @Mock private SubscriptionV2Repository                subscriptionV2Repository;
    @Mock private PaymentMethodRepository                 paymentMethodRepository;

    @InjectMocks
    private SubscriptionPaymentPreferenceServiceImpl preferenceService;

    private static final Long MERCHANT_ID     = 1L;
    private static final Long SUBSCRIPTION_ID = 10L;
    private static final Long CUSTOMER_ID     = 20L;
    private static final Long PRIMARY_PM_ID   = 100L;
    private static final Long BACKUP_PM_ID    = 200L;

    private PaymentMethod activePrimaryPm;
    private PaymentMethod activeBackupPm;

    @BeforeEach
    void setUp() {
        activePrimaryPm = PaymentMethod.builder().id(PRIMARY_PM_ID)
                .status(PaymentMethodStatus.ACTIVE).build();
        activeBackupPm = PaymentMethod.builder().id(BACKUP_PM_ID)
                .status(PaymentMethodStatus.ACTIVE).build();
    }

    // ── Kills Line 63: removed call to setRetryOrderJson ─────────────────────

    @Nested
    @DisplayName("setRetryOrderJson update on existing preference")
    class RetryOrderJsonUpdate {

        @Test
        @DisplayName("update existing pref → retryOrderJson is persisted (kills L63 VoidMethodCall)")
        void updateExisting_retryOrderJson_isPersisted() {
            when(subscriptionV2Repository.findCustomerIdByMerchantIdAndId(MERCHANT_ID, SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(CUSTOMER_ID));
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(MERCHANT_ID, CUSTOMER_ID, PRIMARY_PM_ID))
                    .thenReturn(Optional.of(activePrimaryPm));

            SubscriptionPaymentPreference existing = SubscriptionPaymentPreference.builder()
                    .id(5L).subscriptionId(SUBSCRIPTION_ID)
                    .primaryPaymentMethodId(999L)
                    .retryOrderJson("[999]").build();
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(existing));
            when(preferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            SubscriptionPaymentPreferenceRequestDTO req =
                    SubscriptionPaymentPreferenceRequestDTO.builder()
                            .primaryPaymentMethodId(PRIMARY_PM_ID)
                            .retryOrderJson("[100,200]").build();

            preferenceService.setPaymentPreferences(MERCHANT_ID, SUBSCRIPTION_ID, req);

            ArgumentCaptor<SubscriptionPaymentPreference> cap =
                    ArgumentCaptor.forClass(SubscriptionPaymentPreference.class);
            verify(preferenceRepository).save(cap.capture());
            assertThat(cap.getValue().getRetryOrderJson()).isEqualTo("[100,200]");
        }
    }

    // ── Kills Line 112: status != ACTIVE replaced with false ─────────────────

    @Nested
    @DisplayName("validatePaymentMethod status check")
    class PaymentMethodStatusCheck {

        @Test
        @DisplayName("primary PM with INACTIVE status → 422 PAYMENT_METHOD_NOT_ACTIVE (kills L112 RemoveConditional)")
        void primaryPm_inactive_throws422() {
            when(subscriptionV2Repository.findCustomerIdByMerchantIdAndId(MERCHANT_ID, SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(CUSTOMER_ID));
            PaymentMethod inactivePm = PaymentMethod.builder().id(PRIMARY_PM_ID)
                    .status(PaymentMethodStatus.INACTIVE).build();
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(MERCHANT_ID, CUSTOMER_ID, PRIMARY_PM_ID))
                    .thenReturn(Optional.of(inactivePm));

            SubscriptionPaymentPreferenceRequestDTO req =
                    SubscriptionPaymentPreferenceRequestDTO.builder()
                            .primaryPaymentMethodId(PRIMARY_PM_ID).build();

            assertThatThrownBy(() ->
                    preferenceService.setPaymentPreferences(MERCHANT_ID, SUBSCRIPTION_ID, req))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("not ACTIVE");
        }

        @Test
        @DisplayName("backup PM with EXPIRED status → 422 PAYMENT_METHOD_NOT_ACTIVE")
        void backupPm_expired_throws422() {
            when(subscriptionV2Repository.findCustomerIdByMerchantIdAndId(MERCHANT_ID, SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(CUSTOMER_ID));
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(MERCHANT_ID, CUSTOMER_ID, PRIMARY_PM_ID))
                    .thenReturn(Optional.of(activePrimaryPm));
            PaymentMethod expiredPm = PaymentMethod.builder().id(BACKUP_PM_ID)
                    .status(PaymentMethodStatus.EXPIRED).build();
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(MERCHANT_ID, CUSTOMER_ID, BACKUP_PM_ID))
                    .thenReturn(Optional.of(expiredPm));

            SubscriptionPaymentPreferenceRequestDTO req =
                    SubscriptionPaymentPreferenceRequestDTO.builder()
                            .primaryPaymentMethodId(PRIMARY_PM_ID)
                            .backupPaymentMethodId(BACKUP_PM_ID).build();

            assertThatThrownBy(() ->
                    preferenceService.setPaymentPreferences(MERCHANT_ID, SUBSCRIPTION_ID, req))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("not ACTIVE");
        }
    }

    // ── Kills Line 88: NullReturnVals in getPreferencesForSubscription ───────

    @Nested
    @DisplayName("getPreferencesForSubscription subscription-not-found")
    class GetPreferencesSubscriptionNotFound {

        @Test
        @DisplayName("subscription not found for merchant → 404 (kills L88 NullReturnVals)")
        void getPreferences_subscriptionNotFound_throws404() {
            when(subscriptionV2Repository.findCustomerIdByMerchantIdAndId(MERCHANT_ID, SUBSCRIPTION_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    preferenceService.getPreferencesForSubscription(MERCHANT_ID, SUBSCRIPTION_ID))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("not found");
        }
    }
}
