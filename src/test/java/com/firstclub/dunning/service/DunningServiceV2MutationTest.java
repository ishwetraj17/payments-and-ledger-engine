package com.firstclub.dunning.service;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.billing.service.InvoiceService;
import com.firstclub.dunning.DunningDecision;
import com.firstclub.dunning.DunningDecisionAuditService;
import com.firstclub.dunning.classification.FailureCategory;
import com.firstclub.dunning.classification.FailureCodeClassifier;
import com.firstclub.dunning.entity.DunningAttempt;
import com.firstclub.dunning.entity.DunningAttempt.DunningStatus;
import com.firstclub.dunning.entity.DunningPolicy;
import com.firstclub.dunning.entity.DunningTerminalStatus;
import com.firstclub.dunning.entity.SubscriptionPaymentPreference;
import com.firstclub.dunning.port.PaymentGatewayPort;
import com.firstclub.dunning.port.PaymentGatewayPort.ChargeResult;
import com.firstclub.dunning.repository.DunningAttemptRepository;
import com.firstclub.dunning.repository.DunningPolicyRepository;
import com.firstclub.dunning.repository.SubscriptionPaymentPreferenceRepository;
import com.firstclub.dunning.service.impl.DunningServiceV2Impl;
import com.firstclub.dunning.strategy.BackupPaymentMethodSelector;
import com.firstclub.dunning.strategy.DunningStrategyService;
import com.firstclub.events.service.DomainEventLog;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.payments.dto.PaymentIntentDTO;
import com.firstclub.payments.model.PaymentIntentStatus;
import com.firstclub.payments.service.PaymentIntentService;
import com.firstclub.subscription.entity.SubscriptionStatusV2;
import com.firstclub.subscription.entity.SubscriptionV2;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Mutation-killing tests for {@link DunningServiceV2Impl}.
 *
 * <p>Each test targets a specific surviving or no-coverage mutant identified by
 * PIT baseline analysis.  Tests are grouped by method to aid readability.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DunningServiceV2 Mutation Tests")
class DunningServiceV2MutationTest {

    @Mock private DunningAttemptRepository                dunningAttemptRepository;
    @Mock private DunningPolicyRepository                 dunningPolicyRepository;
    @Mock private SubscriptionPaymentPreferenceRepository preferenceRepository;
    @Mock private SubscriptionV2Repository                subscriptionV2Repository;
    @Mock private InvoiceRepository                       invoiceRepository;
    @Mock private InvoiceService                          invoiceService;
    @Mock private PaymentIntentService                    paymentIntentService;
    @Mock private PaymentGatewayPort                      paymentGatewayPort;
    @Mock private DomainEventLog                          domainEventLog;
    @Mock private DunningPolicyService                    dunningPolicyService;
    @Mock private FailureCodeClassifier                   failureCodeClassifier;
    @Mock private DunningStrategyService                  dunningStrategyService;
    @Mock private BackupPaymentMethodSelector             backupSelector;
    @Mock private DunningDecisionAuditService             decisionAuditService;

    @InjectMocks
    private DunningServiceV2Impl service;

    private static final Long MERCHANT_ID     = 1L;
    private static final Long SUBSCRIPTION_ID = 10L;
    private static final Long INVOICE_ID      = 50L;
    private static final Long POLICY_ID       = 3L;
    private static final Long PRIMARY_PM_ID   = 100L;
    private static final Long BACKUP_PM_ID    = 200L;
    private static final Long ATTEMPT_ID      = 500L;

    private DunningPolicy suspendPolicy;
    private DunningPolicy cancelPolicy;
    private DunningPolicy fallbackPolicy;
    private SubscriptionV2 pastDueSub;
    private Invoice openInvoice;
    private PaymentIntentDTO freshPi;
    private SubscriptionPaymentPreference prefWithBackup;
    private SubscriptionPaymentPreference prefPrimaryOnly;

    @BeforeEach
    void setUp() {
        suspendPolicy = DunningPolicy.builder()
                .id(POLICY_ID).merchantId(MERCHANT_ID).policyCode("DEFAULT")
                .retryOffsetsJson("[60, 360, 1440, 4320]").maxAttempts(4).graceDays(7)
                .fallbackToBackupPaymentMethod(false)
                .statusAfterExhaustion(DunningTerminalStatus.SUSPENDED).build();

        cancelPolicy = DunningPolicy.builder()
                .id(POLICY_ID).merchantId(MERCHANT_ID).policyCode("STRICT")
                .retryOffsetsJson("[30, 60]").maxAttempts(2).graceDays(3)
                .fallbackToBackupPaymentMethod(false)
                .statusAfterExhaustion(DunningTerminalStatus.CANCELLED).build();

        fallbackPolicy = DunningPolicy.builder()
                .id(POLICY_ID).merchantId(MERCHANT_ID).policyCode("FALLBACK")
                .retryOffsetsJson("[60, 360]").maxAttempts(2).graceDays(7)
                .fallbackToBackupPaymentMethod(true)
                .statusAfterExhaustion(DunningTerminalStatus.SUSPENDED).build();

        pastDueSub = SubscriptionV2.builder()
                .id(SUBSCRIPTION_ID)
                .status(SubscriptionStatusV2.PAST_DUE)
                .version(0L).build();

        openInvoice = Invoice.builder()
                .id(INVOICE_ID).status(InvoiceStatus.OPEN)
                .totalAmount(new BigDecimal("499.00")).currency("INR").build();

        freshPi = PaymentIntentDTO.builder()
                .id(300L).invoiceId(INVOICE_ID)
                .amount(new BigDecimal("499.00")).currency("INR")
                .status(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD)
                .clientSecret("cs_test").build();

        prefWithBackup = SubscriptionPaymentPreference.builder()
                .subscriptionId(SUBSCRIPTION_ID)
                .primaryPaymentMethodId(PRIMARY_PM_ID)
                .backupPaymentMethodId(BACKUP_PM_ID).build();

        prefPrimaryOnly = SubscriptionPaymentPreference.builder()
                .subscriptionId(SUBSCRIPTION_ID)
                .primaryPaymentMethodId(PRIMARY_PM_ID)
                .backupPaymentMethodId(null).build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  scheduleAttemptsFromPolicy — surviving mutants
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("scheduleAttemptsFromPolicy mutations")
    class ScheduleAttemptsMutations {

        @Test
        @DisplayName("attemptNumber is i+1, not i-1 — kills MathMutator on line 94")
        void attemptNumber_isOneBased() {
            when(dunningPolicyService.resolvePolicy(MERCHANT_ID)).thenReturn(suspendPolicy);
            when(dunningPolicyService.parseOffsets("[60, 360, 1440, 4320]"))
                    .thenReturn(List.of(60, 360, 1440, 4320));
            when(dunningAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.scheduleAttemptsFromPolicy(SUBSCRIPTION_ID, INVOICE_ID, MERCHANT_ID);

            ArgumentCaptor<DunningAttempt> cap = ArgumentCaptor.forClass(DunningAttempt.class);
            verify(dunningAttemptRepository, times(4)).save(cap.capture());

            List<DunningAttempt> saved = cap.getAllValues();
            assertThat(saved.get(0).getAttemptNumber()).isEqualTo(1);
            assertThat(saved.get(1).getAttemptNumber()).isEqualTo(2);
            assertThat(saved.get(2).getAttemptNumber()).isEqualTo(3);
            assertThat(saved.get(3).getAttemptNumber()).isEqualTo(4);
        }

        @Test
        @DisplayName("created counter increments — kills IncrementsMutator on line 101")
        void createdCounter_incrementsCorrectly() {
            when(dunningPolicyService.resolvePolicy(MERCHANT_ID)).thenReturn(suspendPolicy);
            when(dunningPolicyService.parseOffsets("[60, 360, 1440, 4320]"))
                    .thenReturn(List.of(60, 360, 1440, 4320));
            when(dunningAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.scheduleAttemptsFromPolicy(SUBSCRIPTION_ID, INVOICE_ID, MERCHANT_ID);

            // Verify the domain event records the correct attemptsCreated count
            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Map<String, Object>> mapCap =
                    ArgumentCaptor.forClass(java.util.Map.class);
            verify(domainEventLog).record(eq("DUNNING_V2_SCHEDULED"), mapCap.capture());
            assertThat(mapCap.getValue().get("attemptsCreated")).isEqualTo(4);
        }

        @Test
        @DisplayName("scheduled attempts get correct subscriptionId and invoiceId")
        void scheduledAttempts_haveCorrectForeignKeys() {
            when(dunningPolicyService.resolvePolicy(MERCHANT_ID)).thenReturn(suspendPolicy);
            when(dunningPolicyService.parseOffsets("[60, 360, 1440, 4320]"))
                    .thenReturn(List.of(60, 360, 1440, 4320));
            when(dunningAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.scheduleAttemptsFromPolicy(SUBSCRIPTION_ID, INVOICE_ID, MERCHANT_ID);

            ArgumentCaptor<DunningAttempt> cap = ArgumentCaptor.forClass(DunningAttempt.class);
            verify(dunningAttemptRepository, times(4)).save(cap.capture());

            cap.getAllValues().forEach(a -> {
                assertThat(a.getSubscriptionId()).isEqualTo(SUBSCRIPTION_ID);
                assertThat(a.getInvoiceId()).isEqualTo(INVOICE_ID);
                assertThat(a.getStatus()).isEqualTo(DunningStatus.SCHEDULED);
                assertThat(a.getDunningPolicyId()).isEqualTo(POLICY_ID);
            });
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  processDueV2Attempts — exception handler (no coverage)
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("processDueV2Attempts error handling mutations")
    class ProcessDueErrorHandling {

        @Test
        @DisplayName("exception on processing → attempt marked FAILED with error, terminal check called")
        void exception_marksAttemptFailed_andChecksTerminal() {
            DunningAttempt dueAttempt = buildDueAttempt();
            when(dunningAttemptRepository
                    .findDueForProcessingWithSkipLocked(any(LocalDateTime.class), eq(50)))
                    .thenReturn(List.of(dueAttempt));
            // Force an exception during processSingleV2Attempt
            when(dunningPolicyRepository.findById(POLICY_ID))
                    .thenThrow(new RuntimeException("DB connection lost"));

            service.processDueV2Attempts();

            // Verify attempt is saved with FAILED status and error message
            ArgumentCaptor<DunningAttempt> cap = ArgumentCaptor.forClass(DunningAttempt.class);
            verify(dunningAttemptRepository, atLeast(1)).save(cap.capture());
            DunningAttempt failedAttempt = cap.getAllValues().stream()
                    .filter(a -> a.getId().equals(ATTEMPT_ID))
                    .findFirst().orElseThrow();
            assertThat(failedAttempt.getStatus()).isEqualTo(DunningStatus.FAILED);
            assertThat(failedAttempt.getLastError()).contains("DB connection lost");

            // Verify terminal status check was invoked
            verify(dunningAttemptRepository)
                    .countBySubscriptionIdAndDunningPolicyIdIsNotNullAndStatus(
                            SUBSCRIPTION_ID, DunningStatus.SCHEDULED);
        }

        @Test
        @DisplayName("empty due list → no processing at all — kills RemoveConditionalMutator on line 138")
        void emptyDueList_noInteractions() {
            when(dunningAttemptRepository
                    .findDueForProcessingWithSkipLocked(any(LocalDateTime.class), eq(50)))
                    .thenReturn(Collections.emptyList());

            service.processDueV2Attempts();

            verify(dunningAttemptRepository, never()).save(any());
            verifyNoInteractions(subscriptionV2Repository, invoiceRepository,
                    paymentIntentService, paymentGatewayPort);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  processSingleV2Attempt — policy not found, sub not found (no coverage)
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("processSingleV2Attempt guard clauses")
    class ProcessSingleGuardClauses {

        @Test
        @DisplayName("policy not found → failAttempt called with error — kills line 175-176")
        void policyNotFound_failsAttemptWithError() {
            DunningAttempt dueAttempt = buildDueAttempt();
            stubDueAttempts(dueAttempt);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.empty());

            service.processDueV2Attempts();

            ArgumentCaptor<DunningAttempt> cap = ArgumentCaptor.forClass(DunningAttempt.class);
            verify(dunningAttemptRepository, atLeast(1)).save(cap.capture());
            DunningAttempt saved = cap.getAllValues().stream()
                    .filter(a -> a.getId().equals(ATTEMPT_ID)).findFirst().orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(DunningStatus.FAILED);
            assertThat(saved.getLastError()).contains("Policy not found");
            // Should NOT proceed to load subscription
            verifyNoInteractions(subscriptionV2Repository);
        }

        @Test
        @DisplayName("subscription not found → failAttempt + terminal check — kills line 183-185")
        void subscriptionNotFound_failsAndChecksTerminal() {
            DunningAttempt dueAttempt = buildDueAttempt();
            stubDueAttempts(dueAttempt);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(suspendPolicy));
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

            service.processDueV2Attempts();

            ArgumentCaptor<DunningAttempt> cap = ArgumentCaptor.forClass(DunningAttempt.class);
            verify(dunningAttemptRepository, atLeast(1)).save(cap.capture());
            DunningAttempt saved = cap.getAllValues().stream()
                    .filter(a -> a.getId().equals(ATTEMPT_ID)).findFirst().orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(DunningStatus.FAILED);
            assertThat(saved.getLastError()).contains("Subscription not found");

            // Terminal check should be called
            verify(dunningAttemptRepository)
                    .countBySubscriptionIdAndDunningPolicyIdIsNotNullAndStatus(
                            SUBSCRIPTION_ID, DunningStatus.SCHEDULED);
        }

        @Test
        @DisplayName("subscription not PAST_DUE → attempt marked FAILED with status reason — kills line 190")
        void subscriptionNotPastDue_setsLastError() {
            DunningAttempt dueAttempt = buildDueAttempt();
            SubscriptionV2 activeSub = SubscriptionV2.builder()
                    .id(SUBSCRIPTION_ID).status(SubscriptionStatusV2.ACTIVE).version(0L).build();
            stubDueAttempts(dueAttempt);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(suspendPolicy));
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(activeSub));

            service.processDueV2Attempts();

            ArgumentCaptor<DunningAttempt> cap = ArgumentCaptor.forClass(DunningAttempt.class);
            verify(dunningAttemptRepository).save(cap.capture());
            assertThat(cap.getValue().getStatus()).isEqualTo(DunningStatus.FAILED);
            assertThat(cap.getValue().getLastError()).contains("not PAST_DUE");
            assertThat(cap.getValue().getLastError()).contains("ACTIVE");
        }

        @Test
        @DisplayName("invoice null → failAttempt + terminal check — kills line 198-200")
        void invoiceNull_failsAndChecksTerminal() {
            DunningAttempt dueAttempt = buildDueAttempt();
            stubDueAttempts(dueAttempt);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(suspendPolicy));
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.empty());
            when(dunningAttemptRepository.countBySubscriptionIdAndDunningPolicyIdIsNotNullAndStatus(
                    SUBSCRIPTION_ID, DunningStatus.SCHEDULED)).thenReturn(0L);

            service.processDueV2Attempts();

            ArgumentCaptor<DunningAttempt> cap = ArgumentCaptor.forClass(DunningAttempt.class);
            verify(dunningAttemptRepository, atLeast(1)).save(cap.capture());
            DunningAttempt saved = cap.getAllValues().stream()
                    .filter(a -> a.getId().equals(ATTEMPT_ID)).findFirst().orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(DunningStatus.FAILED);
            assertThat(saved.getLastError()).contains("Invoice not OPEN");
        }

        @Test
        @DisplayName("payment method is set on attempt before charge — kills line 208")
        void paymentMethodId_isSetOnAttempt() {
            DunningAttempt dueAttempt = buildDueAttempt();
            stubDueAttempts(dueAttempt);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(suspendPolicy));
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(openInvoice));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(prefWithBackup));
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            when(paymentGatewayPort.chargeWithCode(300L)).thenReturn(ChargeResult.success());
            when(dunningAttemptRepository.findBySubscriptionIdAndStatus(SUBSCRIPTION_ID, DunningStatus.SCHEDULED))
                    .thenReturn(Collections.emptyList());

            service.processDueV2Attempts();

            ArgumentCaptor<DunningAttempt> cap = ArgumentCaptor.forClass(DunningAttempt.class);
            verify(dunningAttemptRepository, atLeast(1)).save(cap.capture());
            DunningAttempt successAttempt = cap.getAllValues().stream()
                    .filter(a -> a.getId().equals(ATTEMPT_ID) && a.getStatus() == DunningStatus.SUCCESS)
                    .findFirst().orElseThrow();
            assertThat(successAttempt.getPaymentMethodId()).isEqualTo(PRIMARY_PM_ID);
        }

        @Test
        @DisplayName("failure code is set on attempt when charge fails — kills line 216-217")
        void failureCode_isPersistedOnAttempt() {
            DunningAttempt dueAttempt = buildDueAttempt();
            stubDueAttempts(dueAttempt);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(suspendPolicy));
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(openInvoice));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(prefWithBackup));
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            when(paymentGatewayPort.chargeWithCode(300L))
                    .thenReturn(ChargeResult.failed("insufficient_funds"));
            when(failureCodeClassifier.classify("insufficient_funds"))
                    .thenReturn(FailureCategory.INSUFFICIENT_FUNDS);
            when(dunningStrategyService.decide(any(), eq(FailureCategory.INSUFFICIENT_FUNDS), any()))
                    .thenReturn(DunningDecision.RETRY);

            service.processDueV2Attempts();

            ArgumentCaptor<DunningAttempt> cap = ArgumentCaptor.forClass(DunningAttempt.class);
            verify(dunningAttemptRepository, atLeast(1)).save(cap.capture());
            DunningAttempt failedAttempt = cap.getAllValues().stream()
                    .filter(a -> a.getId().equals(ATTEMPT_ID))
                    .findFirst().orElseThrow();
            assertThat(failedAttempt.getFailureCode()).isEqualTo("insufficient_funds");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  handleFailureWithIntelligence — STOP decision (no coverage)
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("handleFailureWithIntelligence — STOP decision")
    class StopDecision {

        @Test
        @DisplayName("STOP → cancels remaining, applies terminal, records event — kills lines 276-282")
        void stopDecision_cancelsAndAppliesTerminal() {
            DunningAttempt dueAttempt = buildDueAttempt();
            stubDueAttempts(dueAttempt);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(suspendPolicy));
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(openInvoice));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(prefWithBackup));
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            when(paymentGatewayPort.chargeWithCode(300L))
                    .thenReturn(ChargeResult.failed("stolen_card"));
            when(failureCodeClassifier.classify("stolen_card"))
                    .thenReturn(FailureCategory.CARD_STOLEN);
            when(dunningStrategyService.decide(any(), eq(FailureCategory.CARD_STOLEN), any()))
                    .thenReturn(DunningDecision.STOP);
            // cancelRemainingV2Attempts will look for SCHEDULED attempts
            when(dunningAttemptRepository.findBySubscriptionIdAndStatus(SUBSCRIPTION_ID, DunningStatus.SCHEDULED))
                    .thenReturn(Collections.emptyList());
            // checkAndApplyTerminalStatus: no remaining scheduled
            when(dunningAttemptRepository.countBySubscriptionIdAndDunningPolicyIdIsNotNullAndStatus(
                    SUBSCRIPTION_ID, DunningStatus.SCHEDULED)).thenReturn(0L);

            service.processDueV2Attempts();

            // Verify DUNNING_V2_STOPPED_EARLY event
            verify(domainEventLog).record(eq("DUNNING_V2_STOPPED_EARLY"), anyMap());
            // Verify audit service called with stoppedEarly=true
            verify(decisionAuditService).record(eq(ATTEMPT_ID), eq(DunningDecision.STOP),
                    contains("Non-retryable"), eq(FailureCategory.CARD_STOLEN), eq(true));
            // Verify cancelRemainingV2Attempts was called
            verify(dunningAttemptRepository).findBySubscriptionIdAndStatus(SUBSCRIPTION_ID, DunningStatus.SCHEDULED);
            // Verify terminal status applied (sub → SUSPENDED)
            ArgumentCaptor<SubscriptionV2> subCap = ArgumentCaptor.forClass(SubscriptionV2.class);
            verify(subscriptionV2Repository).save(subCap.capture());
            assertThat(subCap.getValue().getStatus()).isEqualTo(SubscriptionStatusV2.SUSPENDED);
        }

        @Test
        @DisplayName("STOP with null failureCode → event uses empty string — kills line 281")
        void stopDecision_nullFailureCode_eventUsesEmptyString() {
            DunningAttempt dueAttempt = buildDueAttempt();
            stubDueAttempts(dueAttempt);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(suspendPolicy));
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(openInvoice));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(prefWithBackup));
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            // null failure code
            when(paymentGatewayPort.chargeWithCode(300L))
                    .thenReturn(ChargeResult.failed(null));
            when(failureCodeClassifier.classify(null))
                    .thenReturn(FailureCategory.UNKNOWN);
            when(dunningStrategyService.decide(any(), eq(FailureCategory.UNKNOWN), any()))
                    .thenReturn(DunningDecision.STOP);
            when(dunningAttemptRepository.findBySubscriptionIdAndStatus(SUBSCRIPTION_ID, DunningStatus.SCHEDULED))
                    .thenReturn(Collections.emptyList());
            when(dunningAttemptRepository.countBySubscriptionIdAndDunningPolicyIdIsNotNullAndStatus(
                    SUBSCRIPTION_ID, DunningStatus.SCHEDULED)).thenReturn(0L);

            service.processDueV2Attempts();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Map<String, Object>> mapCap =
                    ArgumentCaptor.forClass(java.util.Map.class);
            verify(domainEventLog).record(eq("DUNNING_V2_STOPPED_EARLY"), mapCap.capture());
            assertThat(mapCap.getValue().get("failureCode")).isEqualTo("");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  handleFailureWithIntelligence — RETRY_WITH_BACKUP (survived + no coverage)
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("handleFailureWithIntelligence — RETRY_WITH_BACKUP")
    class RetryWithBackup {

        @Test
        @DisplayName("no backup available → falls through to terminal status — kills line 316")
        void noBackup_appliesTerminalStatus() {
            DunningAttempt dueAttempt = buildDueAttempt();
            stubDueAttempts(dueAttempt);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(suspendPolicy));
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(openInvoice));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.empty()); // no preference
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            when(paymentGatewayPort.chargeWithCode(300L))
                    .thenReturn(ChargeResult.failed("expired_card"));
            when(failureCodeClassifier.classify("expired_card"))
                    .thenReturn(FailureCategory.CARD_EXPIRED);
            when(dunningStrategyService.decide(any(), eq(FailureCategory.CARD_EXPIRED), any()))
                    .thenReturn(DunningDecision.RETRY_WITH_BACKUP);
            when(backupSelector.findBackup(SUBSCRIPTION_ID)).thenReturn(Optional.empty());
            // No remaining scheduled, so terminal status should be applied
            when(dunningAttemptRepository.countBySubscriptionIdAndDunningPolicyIdIsNotNullAndStatus(
                    SUBSCRIPTION_ID, DunningStatus.SCHEDULED)).thenReturn(0L);

            service.processDueV2Attempts();

            // Terminal status applied → sub saved as SUSPENDED
            ArgumentCaptor<SubscriptionV2> subCap = ArgumentCaptor.forClass(SubscriptionV2.class);
            verify(subscriptionV2Repository).save(subCap.capture());
            assertThat(subCap.getValue().getStatus()).isEqualTo(SubscriptionStatusV2.SUSPENDED);
        }

        @Test
        @DisplayName("backup from selector → creates backup attempt with correct fields — kills lines 288-298")
        void backupFromSelector_createsBackupAttempt() {
            DunningAttempt dueAttempt = buildDueAttempt();
            stubDueAttempts(dueAttempt);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(fallbackPolicy));
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(openInvoice));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(prefWithBackup));
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            when(paymentGatewayPort.chargeWithCode(300L))
                    .thenReturn(ChargeResult.failed("expired_card"));
            when(failureCodeClassifier.classify("expired_card"))
                    .thenReturn(FailureCategory.CARD_EXPIRED);
            when(dunningStrategyService.decide(any(), eq(FailureCategory.CARD_EXPIRED), any()))
                    .thenReturn(DunningDecision.RETRY_WITH_BACKUP);
            when(backupSelector.findBackup(SUBSCRIPTION_ID)).thenReturn(Optional.of(BACKUP_PM_ID));
            when(dunningAttemptRepository.save(any())).thenAnswer(inv -> {
                DunningAttempt a = inv.getArgument(0);
                if (a.getId() == null) a.setId(999L); // Simulate DB-generated ID
                return a;
            });

            service.processDueV2Attempts();

            ArgumentCaptor<DunningAttempt> cap = ArgumentCaptor.forClass(DunningAttempt.class);
            verify(dunningAttemptRepository, atLeast(2)).save(cap.capture());

            // Find the backup attempt (the new one created)
            DunningAttempt backupAttempt = cap.getAllValues().stream()
                    .filter(DunningAttempt::isUsedBackupMethod)
                    .findFirst().orElseThrow(() -> new AssertionError("No backup attempt created"));

            assertThat(backupAttempt.getPaymentMethodId()).isEqualTo(BACKUP_PM_ID);
            assertThat(backupAttempt.getStatus()).isEqualTo(DunningStatus.SCHEDULED);
            assertThat(backupAttempt.getSubscriptionId()).isEqualTo(SUBSCRIPTION_ID);
            assertThat(backupAttempt.getInvoiceId()).isEqualTo(INVOICE_ID);
            assertThat(backupAttempt.getDunningPolicyId()).isEqualTo(POLICY_ID);

            // Verify event includes backupAttemptId when saved.getId() != null
            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Map<String, Object>> mapCap =
                    ArgumentCaptor.forClass(java.util.Map.class);
            verify(domainEventLog).record(eq("DUNNING_V2_BACKUP_QUEUED"), mapCap.capture());
            assertThat(mapCap.getValue()).containsKey("backupAttemptId");
            assertThat(mapCap.getValue().get("backupPmId")).isEqualTo(BACKUP_PM_ID);
        }

        @Test
        @DisplayName("backup from pref fallback when selector empty — kills line 288-289")
        void backupFromPrefFallback_whenSelectorEmpty() {
            DunningAttempt dueAttempt = buildDueAttempt();
            stubDueAttempts(dueAttempt);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(fallbackPolicy));
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(openInvoice));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(prefWithBackup));
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            when(paymentGatewayPort.chargeWithCode(300L))
                    .thenReturn(ChargeResult.failed("expired_card"));
            when(failureCodeClassifier.classify("expired_card"))
                    .thenReturn(FailureCategory.CARD_EXPIRED);
            when(dunningStrategyService.decide(any(), eq(FailureCategory.CARD_EXPIRED), any()))
                    .thenReturn(DunningDecision.RETRY_WITH_BACKUP);
            // Selector returns empty, but pref has backup
            when(backupSelector.findBackup(SUBSCRIPTION_ID)).thenReturn(Optional.empty());
            when(dunningAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.processDueV2Attempts();

            ArgumentCaptor<DunningAttempt> cap = ArgumentCaptor.forClass(DunningAttempt.class);
            verify(dunningAttemptRepository, atLeast(2)).save(cap.capture());

            DunningAttempt backupAttempt = cap.getAllValues().stream()
                    .filter(DunningAttempt::isUsedBackupMethod)
                    .findFirst().orElseThrow(() -> new AssertionError("No backup attempt created"));
            assertThat(backupAttempt.getPaymentMethodId()).isEqualTo(BACKUP_PM_ID);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  handleFailureWithIntelligence — RETRY decision
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("handleFailureWithIntelligence — RETRY decision")
    class RetryDecision {

        @Test
        @DisplayName("RETRY → audit recorded, no terminal action — kills line 268, 272, 320")
        void retryDecision_auditsButNoTerminalAction() {
            DunningAttempt dueAttempt = buildDueAttempt();
            stubDueAttempts(dueAttempt);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(suspendPolicy));
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(openInvoice));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(prefWithBackup));
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            when(paymentGatewayPort.chargeWithCode(300L))
                    .thenReturn(ChargeResult.failed("insufficient_funds"));
            when(failureCodeClassifier.classify("insufficient_funds"))
                    .thenReturn(FailureCategory.INSUFFICIENT_FUNDS);
            when(dunningStrategyService.decide(any(), eq(FailureCategory.INSUFFICIENT_FUNDS), any()))
                    .thenReturn(DunningDecision.RETRY);

            service.processDueV2Attempts();

            // Audit service called with stoppedEarly=false
            verify(decisionAuditService).record(eq(ATTEMPT_ID), eq(DunningDecision.RETRY),
                    contains("Retryable"), eq(FailureCategory.INSUFFICIENT_FUNDS), eq(false));
            // No terminal status check or cancel remaining
            verify(subscriptionV2Repository, never()).save(any());
            verify(dunningAttemptRepository, never()).findBySubscriptionIdAndStatus(
                    eq(SUBSCRIPTION_ID), eq(DunningStatus.SCHEDULED));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  handleFailureWithIntelligence — EXHAUSTED decision
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("handleFailureWithIntelligence — EXHAUSTED decision")
    class ExhaustedDecision {

        @Test
        @DisplayName("EXHAUSTED → audit recorded with correct reason — kills buildDecisionReason line 326")
        void exhaustedDecision_auditsWithCorrectReason() {
            DunningAttempt dueAttempt = buildDueAttempt();
            stubDueAttempts(dueAttempt);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(suspendPolicy));
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(openInvoice));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.empty());
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            when(paymentGatewayPort.chargeWithCode(300L))
                    .thenReturn(ChargeResult.failed("card_declined"));
            when(failureCodeClassifier.classify("card_declined"))
                    .thenReturn(FailureCategory.CARD_DECLINED_GENERIC);
            when(dunningStrategyService.decide(any(), eq(FailureCategory.CARD_DECLINED_GENERIC), any()))
                    .thenReturn(DunningDecision.EXHAUSTED);
            when(dunningAttemptRepository.countBySubscriptionIdAndDunningPolicyIdIsNotNullAndStatus(
                    SUBSCRIPTION_ID, DunningStatus.SCHEDULED)).thenReturn(0L);

            service.processDueV2Attempts();

            // Verify reason string includes "exhausted" keyword
            verify(decisionAuditService).record(
                    eq(ATTEMPT_ID), eq(DunningDecision.EXHAUSTED),
                    contains("exhausted"), eq(FailureCategory.CARD_DECLINED_GENERIC), eq(false));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  checkAndApplyTerminalStatus — survived mutations
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("checkAndApplyTerminalStatus")
    class TerminalStatus {

        @Test
        @DisplayName("remaining > 0 → no terminal action — kills line 346")
        void remainingScheduled_noAction() {
            DunningAttempt dueAttempt = buildDueAttempt();
            stubDueAttempts(dueAttempt);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(suspendPolicy));
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(openInvoice));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.empty());
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            when(paymentGatewayPort.chargeWithCode(300L))
                    .thenReturn(ChargeResult.failed("card_declined"));
            when(failureCodeClassifier.classify("card_declined"))
                    .thenReturn(FailureCategory.CARD_DECLINED_GENERIC);
            when(dunningStrategyService.decide(any(), eq(FailureCategory.CARD_DECLINED_GENERIC), any()))
                    .thenReturn(DunningDecision.EXHAUSTED);
            // Still have remaining scheduled attempts
            when(dunningAttemptRepository.countBySubscriptionIdAndDunningPolicyIdIsNotNullAndStatus(
                    SUBSCRIPTION_ID, DunningStatus.SCHEDULED)).thenReturn(3L);

            service.processDueV2Attempts();

            // No terminal action should be applied
            verify(subscriptionV2Repository, never()).save(any());
            verify(domainEventLog, never()).record(eq("DUNNING_V2_EXHAUSTED"), anyMap());
        }

        @Test
        @DisplayName("sub already not PAST_DUE → no terminal action — kills lambda line 351")
        void subAlreadyResolved_noAction() {
            DunningAttempt dueAttempt = buildDueAttempt();
            SubscriptionV2 activeSub = SubscriptionV2.builder()
                    .id(SUBSCRIPTION_ID).status(SubscriptionStatusV2.ACTIVE).version(0L).build();
            stubDueAttempts(dueAttempt);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(suspendPolicy));
            // For processSingleV2Attempt: sub is PAST_DUE (so it proceeds)
            // But during checkAndApplyTerminalStatus, sub has been changed to ACTIVE
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pastDueSub))  // first call: processSingleV2Attempt
                    .thenReturn(Optional.of(activeSub));   // second call: checkAndApplyTerminalStatus
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(openInvoice));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.empty());
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            when(paymentGatewayPort.chargeWithCode(300L))
                    .thenReturn(ChargeResult.failed("card_declined"));
            when(failureCodeClassifier.classify("card_declined"))
                    .thenReturn(FailureCategory.CARD_DECLINED_GENERIC);
            when(dunningStrategyService.decide(any(), eq(FailureCategory.CARD_DECLINED_GENERIC), any()))
                    .thenReturn(DunningDecision.EXHAUSTED);
            when(dunningAttemptRepository.countBySubscriptionIdAndDunningPolicyIdIsNotNullAndStatus(
                    SUBSCRIPTION_ID, DunningStatus.SCHEDULED)).thenReturn(0L);

            service.processDueV2Attempts();

            // Sub should NOT be saved with terminal status because it's already ACTIVE
            verify(subscriptionV2Repository, never()).save(any());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  cancelRemainingV2Attempts — survived + no coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("cancelRemainingV2Attempts")
    class CancelRemaining {

        @Test
        @DisplayName("remaining scheduled attempts are cancelled on success — kills forEach line 388")
        void remainingAttemptsAreCancelled() {
            DunningAttempt dueAttempt = buildDueAttempt();
            DunningAttempt remaining1 = DunningAttempt.builder()
                    .id(501L).subscriptionId(SUBSCRIPTION_ID).invoiceId(INVOICE_ID)
                    .attemptNumber(2).scheduledAt(LocalDateTime.now().plusMinutes(60))
                    .status(DunningStatus.SCHEDULED).dunningPolicyId(POLICY_ID).build();
            DunningAttempt remaining2 = DunningAttempt.builder()
                    .id(502L).subscriptionId(SUBSCRIPTION_ID).invoiceId(INVOICE_ID)
                    .attemptNumber(3).scheduledAt(LocalDateTime.now().plusMinutes(360))
                    .status(DunningStatus.SCHEDULED).dunningPolicyId(POLICY_ID).build();

            stubDueAttempts(dueAttempt);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(suspendPolicy));
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(openInvoice));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(prefWithBackup));
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            when(paymentGatewayPort.chargeWithCode(300L)).thenReturn(ChargeResult.success());
            when(dunningAttemptRepository.findBySubscriptionIdAndStatus(SUBSCRIPTION_ID, DunningStatus.SCHEDULED))
                    .thenReturn(List.of(remaining1, remaining2));

            service.processDueV2Attempts();

            // Verify remaining attempts were cancelled (status set to FAILED + lastError set)
            ArgumentCaptor<DunningAttempt> cap = ArgumentCaptor.forClass(DunningAttempt.class);
            verify(dunningAttemptRepository, atLeast(3)).save(cap.capture());

            // Find the cancelled remaining attempts
            List<DunningAttempt> cancelled = cap.getAllValues().stream()
                    .filter(a -> a.getId() != null && !a.getId().equals(ATTEMPT_ID)
                            && a.getStatus() == DunningStatus.FAILED)
                    .toList();
            assertThat(cancelled).hasSize(2);
            cancelled.forEach(a -> {
                assertThat(a.getStatus()).isEqualTo(DunningStatus.FAILED);
                assertThat(a.getLastError()).contains("earlier attempt succeeded");
            });
        }

        @Test
        @DisplayName("filter excludes attempts with null dunningPolicyId — kills filter lambda line 387")
        void filterExcludesNullPolicyAttempts() {
            DunningAttempt dueAttempt = buildDueAttempt();
            DunningAttempt v1Attempt = DunningAttempt.builder()
                    .id(600L).subscriptionId(SUBSCRIPTION_ID).invoiceId(INVOICE_ID)
                    .attemptNumber(1).scheduledAt(LocalDateTime.now().plusMinutes(60))
                    .status(DunningStatus.SCHEDULED).dunningPolicyId(null).build(); // v1: no policy

            stubDueAttempts(dueAttempt);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(suspendPolicy));
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(openInvoice));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(prefWithBackup));
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            when(paymentGatewayPort.chargeWithCode(300L)).thenReturn(ChargeResult.success());
            when(dunningAttemptRepository.findBySubscriptionIdAndStatus(SUBSCRIPTION_ID, DunningStatus.SCHEDULED))
                    .thenReturn(List.of(v1Attempt));

            service.processDueV2Attempts();

            // v1 attempt should NOT be cancelled (null dunningPolicyId is filtered out)
            ArgumentCaptor<DunningAttempt> cap = ArgumentCaptor.forClass(DunningAttempt.class);
            verify(dunningAttemptRepository, atLeast(1)).save(cap.capture());

            // The v1 attempt should not appear in saved attempts (it's filtered out by the lambda)
            boolean v1Cancelled = cap.getAllValues().stream()
                    .anyMatch(a -> a.getId() != null && a.getId().equals(600L));
            assertThat(v1Cancelled).isFalse();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  resolvePaymentMethodId — survived + no coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("resolvePaymentMethodId")
    class ResolvePaymentMethod {

        @Test
        @DisplayName("pref null → returns null → paymentMethodId is null — kills line 335")
        void prefNull_returnsNull() {
            DunningAttempt dueAttempt = buildDueAttempt();
            stubDueAttempts(dueAttempt);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(suspendPolicy));
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(openInvoice));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.empty());
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            when(paymentGatewayPort.chargeWithCode(300L)).thenReturn(ChargeResult.success());
            when(dunningAttemptRepository.findBySubscriptionIdAndStatus(SUBSCRIPTION_ID, DunningStatus.SCHEDULED))
                    .thenReturn(Collections.emptyList());

            service.processDueV2Attempts();

            ArgumentCaptor<DunningAttempt> cap = ArgumentCaptor.forClass(DunningAttempt.class);
            verify(dunningAttemptRepository, atLeast(1)).save(cap.capture());
            DunningAttempt successAttempt = cap.getAllValues().stream()
                    .filter(a -> a.getStatus() == DunningStatus.SUCCESS)
                    .findFirst().orElseThrow();
            assertThat(successAttempt.getPaymentMethodId()).isNull();
        }

        @Test
        @DisplayName("usedBackupMethod true + backup available → returns backup PM — kills line 336-337")
        void usedBackup_returnsBackupPm() {
            DunningAttempt dueAttempt = buildDueAttempt();
            dueAttempt.setUsedBackupMethod(true); // flag backup usage
            stubDueAttempts(dueAttempt);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(suspendPolicy));
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(openInvoice));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(prefWithBackup));
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            when(paymentGatewayPort.chargeWithCode(300L)).thenReturn(ChargeResult.success());
            when(dunningAttemptRepository.findBySubscriptionIdAndStatus(SUBSCRIPTION_ID, DunningStatus.SCHEDULED))
                    .thenReturn(Collections.emptyList());

            service.processDueV2Attempts();

            ArgumentCaptor<DunningAttempt> cap = ArgumentCaptor.forClass(DunningAttempt.class);
            verify(dunningAttemptRepository, atLeast(1)).save(cap.capture());
            DunningAttempt successAttempt = cap.getAllValues().stream()
                    .filter(a -> a.getStatus() == DunningStatus.SUCCESS)
                    .findFirst().orElseThrow();
            assertThat(successAttempt.getPaymentMethodId()).isEqualTo(BACKUP_PM_ID);
        }

        @Test
        @DisplayName("usedBackupMethod false → returns primary PM — kills line 339")
        void notBackup_returnsPrimaryPm() {
            DunningAttempt dueAttempt = buildDueAttempt();
            dueAttempt.setUsedBackupMethod(false);
            stubDueAttempts(dueAttempt);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(suspendPolicy));
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(openInvoice));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(prefWithBackup));
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            when(paymentGatewayPort.chargeWithCode(300L)).thenReturn(ChargeResult.success());
            when(dunningAttemptRepository.findBySubscriptionIdAndStatus(SUBSCRIPTION_ID, DunningStatus.SCHEDULED))
                    .thenReturn(Collections.emptyList());

            service.processDueV2Attempts();

            ArgumentCaptor<DunningAttempt> cap = ArgumentCaptor.forClass(DunningAttempt.class);
            verify(dunningAttemptRepository, atLeast(1)).save(cap.capture());
            DunningAttempt successAttempt = cap.getAllValues().stream()
                    .filter(a -> a.getStatus() == DunningStatus.SUCCESS)
                    .findFirst().orElseThrow();
            assertThat(successAttempt.getPaymentMethodId()).isEqualTo(PRIMARY_PM_ID);
        }

        @Test
        @DisplayName("usedBackupMethod true but backup null → returns primary PM — kills line 336 conditional")
        void usedBackupButNullBackup_returnsPrimary() {
            DunningAttempt dueAttempt = buildDueAttempt();
            dueAttempt.setUsedBackupMethod(true);
            stubDueAttempts(dueAttempt);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(suspendPolicy));
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(openInvoice));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(prefPrimaryOnly)); // backup is null
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            when(paymentGatewayPort.chargeWithCode(300L)).thenReturn(ChargeResult.success());
            when(dunningAttemptRepository.findBySubscriptionIdAndStatus(SUBSCRIPTION_ID, DunningStatus.SCHEDULED))
                    .thenReturn(Collections.emptyList());

            service.processDueV2Attempts();

            ArgumentCaptor<DunningAttempt> cap = ArgumentCaptor.forClass(DunningAttempt.class);
            verify(dunningAttemptRepository, atLeast(1)).save(cap.capture());
            DunningAttempt successAttempt = cap.getAllValues().stream()
                    .filter(a -> a.getStatus() == DunningStatus.SUCCESS)
                    .findFirst().orElseThrow();
            assertThat(successAttempt.getPaymentMethodId()).isEqualTo(PRIMARY_PM_ID);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  getAttemptsForSubscription (no coverage)
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getAttemptsForSubscription")
    class GetAttempts {

        @Test
        @DisplayName("valid merchant+sub → returns attempts list")
        void validMerchantAndSub_returnsList() {
            DunningAttempt attempt = buildDueAttempt();
            when(subscriptionV2Repository.findCustomerIdByMerchantIdAndId(MERCHANT_ID, SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(42L));
            when(dunningAttemptRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(List.of(attempt));

            List<DunningAttempt> result = service.getAttemptsForSubscription(MERCHANT_ID, SUBSCRIPTION_ID);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getId()).isEqualTo(ATTEMPT_ID);
        }

        @Test
        @DisplayName("subscription not found → throws SUBSCRIPTION_NOT_FOUND")
        void subscriptionNotFound_throwsException() {
            when(subscriptionV2Repository.findCustomerIdByMerchantIdAndId(MERCHANT_ID, SUBSCRIPTION_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getAttemptsForSubscription(MERCHANT_ID, SUBSCRIPTION_ID))
                    .isInstanceOf(MembershipException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "SUBSCRIPTION_NOT_FOUND");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  forceRetry (no coverage)
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("forceRetry")
    class ForceRetry {

        @Test
        @DisplayName("valid FAILED attempt → creates new SCHEDULED attempt and records event")
        void validFailedAttempt_createsRetry() {
            DunningAttempt failedSource = DunningAttempt.builder()
                    .id(ATTEMPT_ID).subscriptionId(SUBSCRIPTION_ID).invoiceId(INVOICE_ID)
                    .attemptNumber(1).scheduledAt(LocalDateTime.now().minusHours(1))
                    .status(DunningStatus.FAILED).dunningPolicyId(POLICY_ID)
                    .paymentMethodId(PRIMARY_PM_ID).usedBackupMethod(false).build();
            DunningAttempt savedRetry = DunningAttempt.builder()
                    .id(999L).subscriptionId(SUBSCRIPTION_ID).invoiceId(INVOICE_ID)
                    .attemptNumber(1).scheduledAt(LocalDateTime.now())
                    .status(DunningStatus.SCHEDULED).dunningPolicyId(POLICY_ID)
                    .paymentMethodId(PRIMARY_PM_ID).usedBackupMethod(false).build();

            when(dunningAttemptRepository.findById(ATTEMPT_ID)).thenReturn(Optional.of(failedSource));
            when(subscriptionV2Repository.findCustomerIdByMerchantIdAndId(MERCHANT_ID, SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(42L));
            when(dunningAttemptRepository.save(any())).thenReturn(savedRetry);

            DunningAttempt result = service.forceRetry(MERCHANT_ID, ATTEMPT_ID);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(999L);
            assertThat(result.getStatus()).isEqualTo(DunningStatus.SCHEDULED);
            assertThat(result.getSubscriptionId()).isEqualTo(SUBSCRIPTION_ID);
            assertThat(result.getPaymentMethodId()).isEqualTo(PRIMARY_PM_ID);

            verify(domainEventLog).record(eq("DUNNING_V2_FORCE_RETRY"), anyMap());
        }

        @Test
        @DisplayName("attempt not found → throws DUNNING_ATTEMPT_NOT_FOUND")
        void attemptNotFound_throwsException() {
            when(dunningAttemptRepository.findById(ATTEMPT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.forceRetry(MERCHANT_ID, ATTEMPT_ID))
                    .isInstanceOf(MembershipException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "DUNNING_ATTEMPT_NOT_FOUND");
        }

        @Test
        @DisplayName("subscription not found for merchant → throws SUBSCRIPTION_NOT_FOUND")
        void subscriptionNotFoundForMerchant_throwsException() {
            DunningAttempt source = DunningAttempt.builder()
                    .id(ATTEMPT_ID).subscriptionId(SUBSCRIPTION_ID).invoiceId(INVOICE_ID)
                    .attemptNumber(1).status(DunningStatus.FAILED).build();
            when(dunningAttemptRepository.findById(ATTEMPT_ID)).thenReturn(Optional.of(source));
            when(subscriptionV2Repository.findCustomerIdByMerchantIdAndId(MERCHANT_ID, SUBSCRIPTION_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.forceRetry(MERCHANT_ID, ATTEMPT_ID))
                    .isInstanceOf(MembershipException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "SUBSCRIPTION_NOT_FOUND");
        }

        @Test
        @DisplayName("attempt not in FAILED state → throws DUNNING_ATTEMPT_NOT_FAILED — kills line 412")
        void attemptNotFailed_throwsConflict() {
            DunningAttempt scheduledSource = DunningAttempt.builder()
                    .id(ATTEMPT_ID).subscriptionId(SUBSCRIPTION_ID).invoiceId(INVOICE_ID)
                    .attemptNumber(1).status(DunningStatus.SCHEDULED)
                    .scheduledAt(LocalDateTime.now()).build();
            when(dunningAttemptRepository.findById(ATTEMPT_ID)).thenReturn(Optional.of(scheduledSource));
            when(subscriptionV2Repository.findCustomerIdByMerchantIdAndId(MERCHANT_ID, SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(42L));

            assertThatThrownBy(() -> service.forceRetry(MERCHANT_ID, ATTEMPT_ID))
                    .isInstanceOf(MembershipException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "DUNNING_ATTEMPT_NOT_FAILED");
        }

        @Test
        @DisplayName("force retry copies usedBackupMethod from source")
        void forceRetry_copiesBackupFlag() {
            DunningAttempt failedBackup = DunningAttempt.builder()
                    .id(ATTEMPT_ID).subscriptionId(SUBSCRIPTION_ID).invoiceId(INVOICE_ID)
                    .attemptNumber(2).scheduledAt(LocalDateTime.now().minusHours(1))
                    .status(DunningStatus.FAILED).dunningPolicyId(POLICY_ID)
                    .paymentMethodId(BACKUP_PM_ID).usedBackupMethod(true).build();

            when(dunningAttemptRepository.findById(ATTEMPT_ID)).thenReturn(Optional.of(failedBackup));
            when(subscriptionV2Repository.findCustomerIdByMerchantIdAndId(MERCHANT_ID, SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(42L));
            when(dunningAttemptRepository.save(any())).thenAnswer(inv -> {
                DunningAttempt a = inv.getArgument(0);
                a.setId(999L);
                return a;
            });

            DunningAttempt result = service.forceRetry(MERCHANT_ID, ATTEMPT_ID);

            assertThat(result.isUsedBackupMethod()).isTrue();
            assertThat(result.getPaymentMethodId()).isEqualTo(BACKUP_PM_ID);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  failAttempt — survived mutant (setLastError removal)
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("failAttempt side effects")
    class FailAttemptSideEffects {

        @Test
        @DisplayName("failAttempt sets lastError string — kills VoidMethodCallMutator on line 379")
        void failAttempt_setsLastError() {
            DunningAttempt dueAttempt = buildDueAttempt();
            stubDueAttempts(dueAttempt);
            when(dunningPolicyRepository.findById(POLICY_ID)).thenReturn(Optional.of(suspendPolicy));
            when(subscriptionV2Repository.findById(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pastDueSub));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(openInvoice));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(prefWithBackup));
            when(paymentIntentService.createForInvoice(anyLong(), any(), anyString()))
                    .thenReturn(freshPi);
            when(paymentGatewayPort.chargeWithCode(300L))
                    .thenReturn(ChargeResult.failed("card_declined"));
            when(failureCodeClassifier.classify("card_declined"))
                    .thenReturn(FailureCategory.CARD_DECLINED_GENERIC);
            when(dunningStrategyService.decide(any(), eq(FailureCategory.CARD_DECLINED_GENERIC), any()))
                    .thenReturn(DunningDecision.RETRY);

            service.processDueV2Attempts();

            ArgumentCaptor<DunningAttempt> cap = ArgumentCaptor.forClass(DunningAttempt.class);
            verify(dunningAttemptRepository, atLeast(1)).save(cap.capture());
            DunningAttempt failed = cap.getAllValues().stream()
                    .filter(a -> a.getId().equals(ATTEMPT_ID) && a.getStatus() == DunningStatus.FAILED)
                    .findFirst().orElseThrow();
            assertThat(failed.getLastError()).isNotNull();
            assertThat(failed.getLastError()).contains("Payment declined");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private DunningAttempt buildDueAttempt() {
        return DunningAttempt.builder()
                .id(ATTEMPT_ID).subscriptionId(SUBSCRIPTION_ID).invoiceId(INVOICE_ID)
                .attemptNumber(1)
                .scheduledAt(LocalDateTime.now().minusMinutes(1))
                .status(DunningStatus.SCHEDULED)
                .dunningPolicyId(POLICY_ID)
                .usedBackupMethod(false)
                .build();
    }

    private void stubDueAttempts(DunningAttempt... attempts) {
        when(dunningAttemptRepository
                .findDueForProcessingWithSkipLocked(any(LocalDateTime.class), eq(50)))
                .thenReturn(List.of(attempts));
    }
}
